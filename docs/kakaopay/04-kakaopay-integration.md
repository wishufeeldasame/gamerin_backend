# 카카오페이 설계 — 카카오페이 연동 흐름·API (6절)

[← 카카오페이 설계 문서 목차](README.md)

## 6. 카카오페이 연동 흐름·API

### 6-1. 결제 목적과 내부 API

`MILEAGE_CHARGE`와 `MEMBERSHIP_PASS`를 구분한다. 충전·멤버십 모두 서버 상품 코드로 가격을 결정한다. 멤버십 이용권은 서버에서 4,900원으로 결정하며 클라이언트가 보낸 금액으로 덮어쓰지 않는다. 멘토링 신청은 기존 마일리지 차감 계약을 사용한다.

**확정:** 충전은 고정 패키지 선택 방식이다. 클라이언트는 패키지 상품 코드를 보내고 서버가 결제 금액·적립 마일리지를 결정한다. 허용하지 않은 상품 코드는 거부한다. 결제 금액 1원당 1마일리지를 적립하며 보너스는 제공하지 않는다.

| 충전 패키지 결제 금액 | 승인·내부 반영 완료 시 적립액 |
|---|---|
| 5,000원 | 5,000 마일리지 |
| 10,000원 | 10,000 마일리지 |
| 30,000원 | 30,000 마일리지 |
| 50,000원 | 50,000 마일리지 |

| 내부 API | 용도 |
|---|---|
| `GET /api/v1/payments/products` | 서버 상품 정의 목록(충전 패키지 4종 + `MEMBERSHIP_30D`): 상품 코드, 목적, 표시명, 결제 금액, 적립 마일리지 또는 이용 기간. 지갑·멤버십 화면은 가격을 하드코딩하지 않고 이 응답을 표시한다 |
| `POST /api/v1/payments/kakao/ready` | `{ productCode, requestId }`로 주문 준비; 서버가 목적·가격 결정 |
| `POST /api/v1/payments/kakao/approve` | `{ orderId, pgToken }`로 승인 시도 |
| `GET /api/v1/payments` | 본인 결제 목록(지갑 결제 내역). 기존 `CursorPageResponse` 형식, `(createdAt, id)` 내림차순 커서. 대상 상태는 6-6절 |
| `GET /api/v1/payments/{orderId}` | 본인 주문의 결제·혜택 반영 상태 조회 |
| `POST /api/v1/payments/{orderId}/cancel` | 전액 결제 취소·내부 회수. 주문 소유자 본인 + `kakaopay.cancel-enabled = true` + 주문 CID와 설정 CID 일치일 때만 동작(6-6절). 사용자 화면 연결 없음 |
| `GET /api/v1/memberships/me` | 현재 이용 기간·혜택·만료 상태 |

경로·용도는 확정이며 요청·응답 DTO 필드 이름은 구현 중 조정할 수 있다. 상품 정의는 서버 상수(M1과 충전 패키지) 하나이며 상품 목록 응답·ready 가격 결정·화면 표시가 모두 이를 사용한다. 기존 잔액·거래 API는 유지한다. `requestId`는 구매자와 함께 중복 주문을 막는 키다. 같은 키로 다시 요청할 때의 응답은 6-6절 재호출 응답을 따른다. 기존 `ApiResponse<T>` 성공·오류 형식을 유지한다.

오류 응답은 기존 `ResponseStatusException` + `{ success: false, message }` 형식을 따른다.

| 상황 | HTTP |
|---|---|
| 인증 없음 | 401(기존 Security 처리) |
| 정지 사용자 | 403(기존 `UserSuspensionFilter`) |
| 타인 주문 조회·승인·취소 | 403(기존 멘토링 소유자 검사와 같은 방식) |
| 주문 없음 | 404 |
| 허용하지 않은 상품 코드, 요청 형식 오류 | 400 |
| 결제 취소 기능 꺼짐(`cancel-enabled = false`), 주문 CID와 설정 CID 불일치 | 403 |
| 상태상 허용되지 않는 요청(6-6절 재호출 응답의 409), 같은 `requestId`에 다른 상품, 잔액 부족·후속 구매·미완료 이용권 주문으로 인한 취소 거부, `CANCELING` 중 이용권 ready | 409 |
| 설정 누락(`kakaopay.cid`·Secret key 비어 있음), 카카오페이 설정 오류 | 503 |

```text
지갑/멤버십 → 상품 선택 → (PC) 빈 팝업 먼저 열기 → 서버 주문 저장 → 카카오 결제 준비(ready)
→ PC: 팝업을 next_redirect_pc_url로 이동 / 모바일 웹: 현재 창을 next_redirect_mobile_url로 이동
→ 사용자 인증 → approval_url(/payments/result?orderId=…&pg_token=…) 복귀
→ PC: 팝업이 부모 창에 결과 전달 후 닫힘, 부모 창이 승인 요청 / 모바일: 결과 페이지가 인증 복구 후 승인 요청
→ 서버 승인(approve)·검증 → 충전 적립 또는 멤버십 기간 부여 → 결과 표시
```

### 6-2. 승인·복구 규칙

1. 주문 소유자, 저장한 상품·금액·CID·TID·주문번호·사용자 식별자를 검증한다. 브라우저 복귀·pg_token 수신 자체는 결제 성공이 아니다.
2. 외부 호출 전에 복구 가능한 주문·처리 상태를 DB에 남긴다. 짧은 트랜잭션으로 승인 처리권을 확보하고 외부 호출 동안 지갑 잠금을 잡지 않는다.
3. 승인 확인 후 하나의 DB 트랜잭션에서 결제 확정과 원장 적립 또는 멤버십 부여 이력을 반영한다. `APPROVED`는 내부 반영 완료까지 의미한다.
4. 동시 승인·새로고침에도 적립·기간 연장은 한 번만 수행한다. 상태 제어와 원장/부여 이력의 결제 참조 UNIQUE 제약으로 보호한다. **TID UNIQUE만으로 내부 중복 반영까지 해결되지는 않는다.**
5. 타임아웃·승인 후 DB 실패·프로세스 중단은 `APPROVING`으로 남기고(화면 표시 `결제 확인 중`) TID로 주문 조회 후 복구한다(6-6절). 결과 불명을 실패로 단정하거나 새 결제를 유도하지 않는다.
6. catch에서 취소 API를 한 번 호출하는 것만으로 복구를 끝내지 않는다. 취소도 실패할 수 있고 커밋 실패는 메서드 밖에서 발생할 수 있다. 미확정 주문은 6-6절의 조회 시점 복구와 5분 스케줄러로 재조회하고, 자동 판정할 수 없는 주문은 `NEEDS_REVIEW`로 남긴다.
7. cancel/fail 브라우저 복귀는 표시용 신호다. 승인된 주문을 덮어쓰거나 환불하지 않는다. 실제 취소는 결제사 상태·적립 사용 여부를 확인하는 별도 절차다.
8. 충전 `referenceId = payment.id`를 기록한다. 전액 결제 취소 시 내부 잔액·이용 기간 회수와 실패 복구까지 처리한다. 회수 선반영과 사용자 단위 직렬화(6-6절 결제 취소)로 취소 중 잔액 사용·이용권 재구매가 끼어들지 않게 하고, 외부 요청 중에는 DB 잠금을 장시간 유지하지 않는다. 취소 결과 불명은 조회로 복구하며 외부 취소와 내부 회수를 각각 한 번만 반영한다.
9. **ready 응답 유실:** 두 구간을 구분한다.
   - 카카오페이 → 백엔드: TID를 받지 못했으므로 결제가 시작되지 않은 상태다. 주문을 `FAILED`로 닫고 사용자가 새로 결제를 시작하게 한다. 카카오페이는 가맹점 주문번호로 조회하는 API를 제공하지 않는다.
   - 백엔드 → 브라우저: 서버는 `READY`와 결제창 URL을 저장했다. 브라우저가 같은 `requestId`로 다시 요청하면 기존 주문과 저장된 URL을 돌려준다(6-6절 재호출 응답).
10. **15분 만료:** TID와 결제 요청은 15분간 유효하며 경과 시 카카오페이가 `fail_url`로 보낸다. 서버는 ready 시각 + 15분이 지난 `READY` 주문을 주문 조회로 확인해 `SUCCESS_PAYMENT`가 아니면 `EXPIRED`로 닫는다. 만료된 주문의 approve 요청은 거부한다.
11. **카카오페이 상태 매핑:** 주문 조회 `status`를 내부 상태로 변환한다. `SUCCESS_PAYMENT` → 승인 확정·내부 반영, `CANCEL_PAYMENT` → 취소 확정·내부 회수, `PART_CANCEL_PAYMENT` → 이번 범위에서 발생하지 않아야 하므로 `NEEDS_REVIEW`, `FAIL_PAYMENT`·`QUIT_PAYMENT`·`FAIL_AUTH_PASSWORD` → 실패, `READY`·`SEND_TMS`·`OPEN_PAYMENT`·`SELECT_METHOD`·`ARS_WAITING`·`AUTH_PASSWORD` → 진행 중(만료 전 재조회).
12. **오류 분류:** 카카오페이 오류는 `{ error_code, error_message, extras.method_result_code, extras.method_result_message }` 형식이다. **HTTP 상태만으로 결제 성립 여부를 판정하지 않는다.** 공식 결제 오류코드 v1.7에는 `-702`(이미 결제 완료된 TID 재승인), `-785`(결제·취소 중복 요청), `-780`의 `BANK_FIRM_UNKNOWN`(은행 처리 결과 미확인)·`USER_LOCKED`(동일 사용자 거래 처리 중)처럼 4xx지만 결제가 성립했거나 결과가 불명확한 경우가 있다.
    - **ready 단계 4xx:** TID가 발급되지 않았으므로 `FAILED`로 닫는다.
    - **approve·cancel 단계 4xx(`-780`, `-781`, `-702`, `-785` 등 전부):** 실패로 단정하지 않는다. 처리권을 가진 작업자가 즉시 TID 주문 조회를 하고 11번 매핑대로 전이한다. 조회 결과가 진행 중이거나 조회도 실패하면 상태를 유지하고 6-6절 복구로 넘긴다.
    - **타임아웃·연결 끊김·5xx(`-500`, `-503`):** 결과 불명. 상태를 유지하고 주문 조회로 복구한다.
    - **설정 오류(`-401` 키 오류, `-403` 사용 API 미등록, `-429` 쿼터 초과, `-731` 잘못된 CID):** 오류 로그·알림 대상이다. ready 단계면 `FAILED`, approve·cancel 단계면 상태를 유지하고 설정을 바로잡은 뒤 복구로 정리한다.
    - `error_message`·`extras` 메시지를 사용자에게 그대로 노출하지 않고, 로그에는 코드만 남긴다.

주문 조회는 TID로 상태를 확인하는 공식 API다. 카카오페이 포럼 답변은 ready 응답 실패 시 처음부터 다시 결제하고, approve 응답 실패 시 TID로 주문 조회하라고 안내한다. [주문 조회](https://developers.kakaopay.com/docs/payment/online/payment-detail), [응답 실패 처리 안내](https://developers.kakaopay.com/forum/t/topic/180), [TID 유효시간 15분](https://developers.kakaopay.com/forum/t/api/657/2), [참고하기·오류 형식](https://developers.kakaopay.com/docs/payment/online/reference)

### 6-3. 인증·환경

- 결과 페이지가 직접 승인할 때(모바일·부모 창 부재)는 `isAuthReady` 이후 승인한다. 팝업은 새 브라우징 컨텍스트라 메모리 access token이 없으므로, PC에서는 이미 인증된 부모 창이 승인하는 것을 기본으로 한다. 계정 전환 시 서버가 주문 소유자 불일치를 거부한다.
- **재로그인 시 승인 재개 없음(확정):** 현재 저장 사용자가 없으면 refresh를 시도하지 않으며 로그인/OAuth 성공은 `/home`으로 이동한다. 서버에 승인 재개 문맥을 저장하지 않는다. 결과 페이지가 인증을 복구하지 못하면 approve를 호출하지 않고 로그인 안내를 표시한다. 미승인 주문은 15분 만료로 정리되며 사용자는 로그인 후 새로 결제한다. 이미 `APPROVING`·`APPROVED` 등인 주문은 로그인 후 지갑 결제 내역·주문 조회로 결과만 확인하고 새 결제를 유도하지 않는다. pg_token은 URL·`localStorage`·`sessionStorage`·로그에 남기지 않는다. PC는 인증된 부모 창이 승인하고, 모바일은 같은 탭으로 복귀해 저장 사용자와 refresh 쿠키가 유지되므로 이 경로는 예외 상황이다.
- Secret key·결제 토큰을 로그에 남기지 않는다. 처리 후 결과 URL의 토큰을 제거하고 프록시 access log의 쿼리 기록도 확인한다.
- 공식 단건 문서에 HTTPS `open-api.kakaopay.com`, `Authorization: SECRET_KEY ...`, `Content-Type: application/json`, 가맹점 코드(CID), approval 복귀의 pg_token이 안내되어 있다. 필드 규격은 6-5절에 정리했다. [단건 결제](https://developers.kakaopay.com/docs/payment/online/single-payment)
- Secret key와 CID는 코드에 고정하지 않고 설정(`kakaopay.api.secret-key`, `kakaopay.cid`)으로 주입한다. 개발자센터는 Secret key(dev)·CID `TC0ONETIME`과 Secret key(prod)·가맹점 CID를 구분해 발급하며, 두 경우 모두 같은 호스트(`open-api.kakaopay.com`)를 쓴다. 코드는 키 종류를 구분하지 않고, 설정된 CID 하나만 허용해 요청·응답·저장 CID가 이와 일치하는지 검사한다. `TC` 접두사 같은 형식 검사로 대체하지 않는다. [단건 결제](https://developers.kakaopay.com/docs/payment/online/single-payment), [REST API 서버환경](https://developers.kakaopay.com/docs/getting-started/api-common-guide/restapi)
- 인증 헤더는 `SECRET_KEY {Secret key}`다(Secret key(dev)는 `DEV_SECRET_KEY` 접두사도 허용). 키를 재발급하면 기존 키는 즉시 폐기되므로 노출 시 재발급 후 backend 환경변수를 교체한다. [인증](https://developers.kakaopay.com/docs/getting-started/api-common-guide/authentication), [사전 준비사항](https://developers.kakaopay.com/docs/getting-started/api-common-guide/prepare)
- **구 API와 혼동 금지:** 인터넷 예제 다수가 쓰는 `kapi.kakao.com` + `Authorization: KakaoAK {ADMIN_KEY}` + form-urlencoded는 (구)온라인 결제 API다. 신규 API는 `open-api.kakaopay.com/online/v1/payment/*` + `SECRET_KEY` + JSON이며 오류 필드도 `error_code`/`error_message`로 다르다. 요청·응답 필드는 같다. [전환하기](https://developers.kakaopay.com/docs/payment/online/change)
- **TLS:** 카카오페이 API는 TLS 1.2만 지원한다(1.3 미지원). Java 21 기본 클라이언트는 1.2로 협상하므로 별도 설정은 필요 없을 것으로 보지만, 연결 실패 시 우선 확인한다.
- **타임아웃:** 카카오페이 플랫폼의 요청 타임아웃은 11초다. `KakaoPayClient`의 read timeout은 11초보다 길게(예: 15초) 두고, 초과 시 6-2절 결과 불명 처리로 넘긴다. [참고하기](https://developers.kakaopay.com/docs/payment/online/reference)
- **도메인 등록:** [애플리케이션 > 플랫폼 > Web]에 사이트 도메인을 등록하며 approval/cancel/fail URL의 도메인이 일치해야 한다. 사이트 도메인은 프로토콜 포함·path 없이 등록하고(최대 10개, 와일드카드 가능), http와 https는 각각 등록한다. Secret key(dev)를 쓰는 앱에서는 `http://localhost:포트`, `http://127.0.0.1`, `http://공인IP`도 등록할 수 있다. 서비스 도메인은 `FRONTEND_BASE_URL`과 같은 값으로 등록한다. [플랫폼](https://developers.kakaopay.com/docs/getting-started/applications/platform)
- **사용 API 등록:** [애플리케이션 > 사용 API]에 온라인 결제를 등록하지 않으면 `-403`으로 실패한다. [사용 API](https://developers.kakaopay.com/docs/getting-started/applications/using-api)
- **사업자 등록:** 온라인 결제는 비즈 API이지만, 카카오페이 포럼 답변에 따르면 사업자 등록 없이 개발자센터 가입·앱 생성으로 받은 Secret key(dev)로 연동할 수 있다. Secret key(prod)·가맹점 CID 발급에는 사업자 등록·비즈앱 전환·가맹점 심사가 필요하다. 코드는 키·CID 설정 교체만으로 전환되도록 작성한다. [포럼 답변](https://developers.kakaopay.com/forum/t/api/337/2), [API 및 애플리케이션 유형](https://developers.kakaopay.com/docs/getting-started/basic/api-and-application-type)
- **CSP·헤더:** 팝업과 페이지 이동은 최상위 내비게이션이므로 프론트 CSP(`next.config.ts`)의 `connect-src`/`form-action`/`frame-src`에 카카오 도메인을 추가할 필요가 없다. `X-Frame-Options: DENY`는 iframe을 쓰지 않으므로 영향이 없다. 현재 `Cross-Origin-Opener-Policy`는 설정하지 않았다. 이후 `same-origin`을 도입하면 카카오 도메인을 거쳐 돌아온 팝업의 `window.opener`가 끊기므로 6-4절 폴백만 동작한다.
- **로그:** pg_token이 결과 URL 쿼리에 실리므로 nginx access log에 남는다. `/payments/result` 경로의 쿼리 기록을 제외하거나 마스킹한다. `Referrer-Policy: strict-origin-when-cross-origin`이므로 외부로는 origin만 전달된다.

### 6-4. 결제창 호출 방식 (확정: PC 팝업)

카카오페이 공식 문서는 PC에서 `next_redirect_pc_url`을 팝업 또는 레이어로, 모바일 웹에서 `next_redirect_mobile_url`을 페이지 이동(웹뷰)으로 띄우도록 안내하고 iframe은 권고하지 않는다. GamerIN은 PC 팝업을 확정했다. 네이티브 앱이 없으므로 `next_redirect_app_url`, `redirect_scheme_url`, `custom_json.auto_close`는 사용하지 않는다. [이해하기](https://developers.kakaopay.com/docs/payment/online/common), [부가기능](https://developers.kakaopay.com/docs/payment/online/add-on)

| 단계 | PC (팝업) | 모바일 웹 (페이지 이동) |
|---|---|---|
| 진입 | 결제 버튼 클릭 핸들러에서 **동기적으로** `window.open('', 'kakaopay', 크기)`로 빈 팝업을 먼저 연다. ready 응답을 기다린 뒤 열면 팝업 차단에 걸린다. | ready 응답 후 `location.assign(next_redirect_mobile_url)` |
| 차단 처리 | 팝업이 `null`이면 ready를 호출하지 않고 "팝업 차단 해제" 안내를 표시한다. ready 실패 시 빈 팝업을 닫는다. | 해당 없음 |
| 결제창 | 팝업의 `location`을 `next_redirect_pc_url`로 바꾼다. 사용자는 QR 또는 카카오톡 메시지(TMS)로 결제한다. 부모 창은 `결제 진행 중` 상태와 팝업 다시 열기·취소 버튼을 보여준다. | 카카오페이 결제 화면으로 이동 |
| 복귀 | 팝업이 `approval_url`·`cancel_url`·`fail_url`(모두 `/payments/result?orderId=…&result=…`)로 돌아온다. | 현재 창이 같은 URL로 돌아온다. |
| 결과 전달 | 결과 페이지가 `window.opener`가 있고 같은 origin이면 `postMessage({ type: 'kakaopay-result', orderId, result, pgToken }, location.origin)`로 전달하고 팝업을 닫는다. 부모 창은 `event.origin`과 `orderId`를 검증한 뒤 approve를 호출한다. | 결과 페이지가 인증 복구 후 approve를 호출한다. |
| 폴백 | 부모 창이 닫혔거나 이동해 `opener`가 없으면 팝업 안에서 모바일과 같은 방식으로 인증 복구 후 승인하고 결과를 표시한다. | — |
| 팝업 수동 종료 | 부모 창이 팝업 `closed`를 감지하면 서버 주문 상태를 조회한다. 승인 전이면 `결제가 취소되었습니다`로 표시하고, 서버는 15분 만료 규칙으로 정리한다. | — |

- PC·모바일 판정은 클라이언트에서 한다. 서버 ready 응답은 `pcUrl`, `mobileUrl`을 함께 내려주고 TID는 내려주지 않는다.
- pg_token은 postMessage와 approve 요청 본문으로만 전달하고 `localStorage`·`sessionStorage`에 저장하지 않는다. 결과 페이지는 처리 직후 `history.replaceState`로 쿼리를 제거한다.
- 같은 주문의 approve가 부모 창과 팝업 폴백에서 중복 호출될 수 있으므로 6-2절의 멱등 처리로 한 번만 반영한다.

### 6-5. 카카오페이 API 규격 대조 (2026-10-10 공식 문서 기준)

| API | 요청 필드 매핑 | 응답에서 저장·검증 |
|---|---|---|
| ready `POST /online/v1/payment/ready` | `cid`=설정 CID(`kakaopay.cid`), `partner_order_id`=내부 주문 ID(UUID, 100자 이하), `partner_user_id`=내부 user UUID(실명·전화번호·이메일·로그인 ID 등 **개인정보 전송 불가**), `item_name`=상품 표시명(100자 이하), `item_code`=상품 코드, `quantity`=1, `total_amount`=서버 상품 가격, `tax_free_amount`=0(**필수**), `vat_amount` 생략(자동 계산), `approval_url`·`cancel_url`·`fail_url`=결과 URL(각 255자 이하, 등록 도메인과 일치) | `tid` 저장, 15분 만료 기준은 서버가 기록한 ready 시각(카카오페이 `created_at`은 참고용 저장, 시간대 해석 문제와 무관하게 만료 판정), `next_redirect_pc_url`·`next_redirect_mobile_url`은 클라이언트 전달용 |
| approve `POST /online/v1/payment/approve` | `cid`, `tid`, `partner_order_id`·`partner_user_id`(**ready와 일치 필수**), `pg_token`, `total_amount`(선택이지만 저장 가격으로 보내 금액 불일치를 카카오페이에서도 거부하게 함) | `aid`, `approved_at`, `amount.total`이 저장 가격과 같은지, `partner_order_id`·`partner_user_id`·`cid`·`tid` 일치, `payment_method_type`(CARD/MONEY) 기록. 카드 상세(`card_info`)는 저장하지 않음 |
| order `POST /online/v1/payment/order` | `cid`, `tid` | `status`(6-2절 매핑), `amount`, `canceled_amount`, `cancel_available_amount`, `payment_action_details[]`(`aid`, `payment_action_type` PAYMENT/CANCEL) |
| cancel `POST /online/v1/payment/cancel` | `cid`, `tid`, `cancel_amount`=결제 금액 전액, `cancel_tax_free_amount`=0(**필수**), `cancel_available_amount`=저장된 결제 금액(카카오페이 쪽 잔여 금액이 다르면 거부되게 함) | `aid`, `status`=`CANCEL_PAYMENT`, `canceled_at`, `approved_cancel_amount.total`이 요청 금액과 같은지 |

- 요청·응답 시각(`created_at`, `approved_at`, `canceled_at`)은 오프셋 없는 `yyyy-MM-ddTHH:mm:ss` 형식이다. KST로 해석해 `OffsetDateTime`으로 저장한다(`확인 필요`: 공식 문서에 시간대 명시 없음).
- 공통 오류 코드: `-400` Authorization 누락, `-401` Secret key 오류, `-403` 사용 API 미등록, `-404` URL 오류, `-429` 일일 쿼터 초과(매일 0시 초기화, 온라인 결제의 구체적 허용량은 문서에 없음), `-500` 내부 오류, `-503` 점검. 결제별 상세 오류 코드는 공식 엑셀(결제 오류코드 v1.7)로 제공된다. 2026-10-10 검토에서 엑셀을 내려받아 대조했고 그 결과를 6-2절 12번에 반영했다. 엑셀에는 구 API 공통 설명이 섞여 있으므로 공통 오류 형식은 신규 공식 문서를 기준으로 한다. [에러 코드](https://developers.kakaopay.com/docs/getting-started/api-common-guide/error-code), [쿼터](https://developers.kakaopay.com/docs/getting-started/api-common-guide/quota)
- 결제 수단 제한(`payment_method_type`), 할부(`install_month`), 카드사 지정(`available_cards`), 컵 보증금(`green_deposit`)은 사용하지 않는다.
- 카카오페이 머니 결제 시 현금영수증은 카카오페이가 자동 발행하며 가맹점이 별도로 발행하지 않는다.
- 방화벽으로 아웃바운드를 제한하는 환경이면 결제 API 서버(`open-api.kakaopay.com`)의 공식 IP를 허용한다. 현재 Docker 환경은 아웃바운드를 제한하지 않는 것으로 보이며 `확인 필요`.

### 6-6. 결제 상태·복구 실행·결제 취소 계약 (2026-10-10 확정)

#### 상태

결과 불명은 별도 상태로 두지 않는다. `APPROVING`·`CANCELING`이 **1분 이상**(`status_changed_at` 기준, read timeout 15초보다 충분히 긴 값) 머물면 결과 불명으로 보고 주문 조회로 복구한다.

| 상태 | 의미 | 다음 상태 |
|---|---|---|
| `CREATED` | 주문 저장, ready 호출 전·중 | `READY`(ready 성공, TID 저장) / `FAILED`(ready 확정 실패·응답 유실, 또는 1분 경과) |
| `READY` | TID 발급, 사용자 결제 대기 | `APPROVING`(approve 처리권 확보) / `EXPIRED`(ready + 15분 경과, 조회 결과 `SUCCESS_PAYMENT` 아님) / `FAILED`(조회 결과 `FAIL_PAYMENT`·`QUIT_PAYMENT`·`FAIL_AUTH_PASSWORD`) |
| `APPROVING` | approve 호출 중 또는 결과 불명 | `APPROVED` / `FAILED`(주문 조회 결과 실패) / `EXPIRED`(ready + 15분 경과 후 조회 결과 진행 중) / `NEEDS_REVIEW`(금액·식별자 불일치) |
| `APPROVED` | 카카오페이 승인 + 내부 반영(적립 또는 기간 부여) 완료 | `CANCELING`(결제 취소 처리권 확보) |
| `CANCELING` | 내부 회수 반영 후 외부 취소 호출 중 또는 결과 불명 | `CANCELED` / `NEEDS_REVIEW`(외부 취소가 응답으로 거부되고 조회 결과도 취소되지 않음) |
| `CANCELED` | 외부 취소 + 내부 회수 완료 | 종료 |
| `FAILED`, `EXPIRED` | 결제되지 않음 | 종료. approve 요청은 거부 |
| `NEEDS_REVIEW` | 자동 판정 불가. 로그를 남기고 DB에서 수동 확인 | 종료(관리자 화면은 만들지 않음) |

상태는 위 9개이며 Java enum·DB CHECK·상태 전이 테스트가 같은 목록을 사용한다.

- **처리권:** 모든 전이와 복구 착수는 `UPDATE payments SET status = :next, status_changed_at = :now WHERE id = :id AND status = :observedStatus AND status_changed_at = :observedChangedAt`로 확보한다. 갱신 행 수가 1이면 처리권을 얻은 것이고, 0이면 다른 작업자가 처리 중이므로 외부 호출 없이 현재 상태만 반환한다. 오래된 `APPROVING`·`CANCELING`을 복구할 때는 상태를 그대로 두고 `status_changed_at`만 갱신해 처리권을 잡는다. 외부 호출 후의 결과 반영도 자신이 기록한 `status_changed_at`이 그대로일 때만 같은 트랜잭션에서 내부 반영과 함께 커밋한다. 처리권을 잃은 늦은 응답은 롤백되고 새 상태를 덮어쓰지 않는다.
- 내부 반영의 최종 방어는 원장 `(type, reference_id)` 부분 UNIQUE와 `membership_grants.payment_id` UNIQUE다.
- 주문 조회로 복구할 때 `SUCCESS_PAYMENT`이면 응답의 `amount.total`과 `payment_action_details[]`의 `aid`로 approve 응답과 같은 검증을 한 뒤 내부 반영하고 `APPROVED`로 전환한다.
- 브라우저의 cancel/fail 복귀는 상태를 바꾸지 않는다. 화면은 주문 조회 결과를 따른다.

#### 복구 실행 (조회 시점 + 스케줄러)

- **조회 시점:** `GET /api/v1/payments/{orderId}`와 approve 요청이 대상 주문을 결과 불명(`APPROVING`·`CANCELING` 1분 이상) 또는 만료 후보(`READY` 15분 경과)로 판정하면 즉시 카카오페이 주문 조회로 복구를 시도하고 그 결과를 반환한다.
- **스케줄러:** `domain/payment`의 복구 스케줄러가 5분 주기로 실행한다(기존 `@EnableScheduling` 사용).
  - `CREATED` 1분 이상 → `FAILED`(TID가 없어 조회할 수 없음)
  - `READY` 15분 경과 → 주문 조회 후 `EXPIRED`·`FAILED`
  - `APPROVING`·`CANCELING` 1분 이상 → 주문 조회 후 위 표대로 전이
- 카카오페이 조회 자체가 실패(타임아웃·5xx)하면 상태를 유지하고 다음 주기에 다시 시도한다. 설정 오류(`-401`·`-403`·`-429`·`-731`)는 오류 로그를 남긴다.
- `CANCELING` 복구에서 조회 결과가 `SUCCESS_PAYMENT`(외부 취소 미반영)이고 이전 취소 호출이 확정 응답을 받지 못한 경우(타임아웃·서버 중단)는 외부 취소를 다시 호출한다. `cancel_available_amount`를 저장 금액으로 보내므로 이중 취소되지 않는다. 취소가 4xx로 거부되고 조회 결과도 `SUCCESS_PAYMENT`이면 `NEEDS_REVIEW`로 둔다.

#### 재호출 응답

브라우저가 응답을 받지 못해 같은 요청을 다시 보내도 외부 호출·적립·기간 부여·회수는 중복되지 않는다. 응답은 기존 `ApiResponse` 형식의 결제 상태 DTO(`orderId`, `status`, 목적·상품·금액 등)를 사용한다.

| 요청 | 기존 주문 상태 | 응답 |
|---|---|---|
| ready(같은 구매자·`requestId`) | 상품 코드가 다름 | 409 거부 |
| | `CREATED` | 200, 상태 `CREATED`(처리 중), URL 없음 |
| | `READY`(ready + 15분 이내) | 200, 저장된 `pcUrl`·`mobileUrl` 재응답 |
| | 그 밖의 상태 | 200, 현재 상태만. 새 결제는 새 `requestId`로 시작 |
| approve | `READY` | 승인 진행 |
| | `APPROVING` | 200, 현재 상태(결과 불명이면 조회 시점 복구 후 결과) |
| | `APPROVED`·`CANCELING`·`CANCELED`·`NEEDS_REVIEW` | 200, 현재 상태. 외부 호출 없음 |
| | `CREATED`·`FAILED`·`EXPIRED` | 409 거부 |
| cancel | `APPROVED` | 취소 진행(권한·조건 검사) |
| | `CANCELING` | 200, 현재 상태(결과 불명이면 조회 시점 복구 후 결과) |
| | `CANCELED` | 200, 현재 상태. 외부 호출 없음 |
| | 그 밖의 상태 | 409 거부 |

- 결제창 URL(`next_redirect_pc_url`·`next_redirect_mobile_url`)은 `payments`에 저장하되 `READY`이고 15분 이내일 때만 응답하며 로그에 남기지 않는다.

#### 결제 목록

- `GET /api/v1/payments`는 본인 결제만 반환한다. 대상은 실제 결제가 일어났거나 확인 중인 `APPROVING`·`APPROVED`·`CANCELING`·`CANCELED`·`NEEDS_REVIEW`이며, 화면에는 `APPROVING`·`CANCELING`·`NEEDS_REVIEW`를 `결제 확인 중`으로 표시한다. 결제가 일어나지 않은 `CREATED`·`READY`·`FAILED`·`EXPIRED`는 제외한다.
- 필드: `orderId`, 목적, 상품명, 금액, 상태, 결제 수단(CARD/MONEY), 생성·승인·취소 시각. TID·AID·CID·URL은 내려주지 않는다.
- 마일리지 원장과 별도 탭으로 표시해 10,000원 결제와 10,000 마일리지 적립이 이중 지출처럼 보이지 않게 한다. 카카오페이 주문 조회는 저장된 TID의 상태 확인 용도이며 이 목록을 대체하지 않는다.
- 한 번에 처리할 주문 수를 제한한다. 외부 호출 동안 DB 잠금을 유지하지 않는다.

#### 결제 취소

1. **권한·기능 플래그:** 주문 소유자 본인이어야 한다. `kakaopay.cancel-enabled = true`이고, 주문 CID가 설정 CID와 같으며, 상태가 `APPROVED`여야 한다. 하나라도 어긋나면 거부한다(재호출은 위 표). 플래그 기본값은 `false`다.
2. **내부 회수 선반영(한 트랜잭션):** 결제를 `APPROVED → CANCELING`으로 전환하고 회수를 함께 반영한다. 회수를 먼저 반영해 외부 취소 중에 잔액 사용·재구매가 끼어들지 못하게 한다.
   - 충전: 지갑을 잠그고 잔액이 충전액 이상인지 확인한 뒤 `CHARGE_CANCEL`(−충전액, `reference_id = payment.id`)을 기록한다. 부족하면 거부한다.
   - 이용권: User 행과 `user_memberships`를 잠그고 대상 grant가 마지막 `GRANTED`인지 확인한다. 같은 사용자에게 `CREATED`·`READY`·`APPROVING` 상태의 이용권 주문이 있으면 거부한다. `expires_at = granted_from`으로 되돌리고 grant를 `REVOKED`로 바꾼다. 후속 구매가 있으면 거부한다.
   - 이용권 ready도 같은 User 행 잠금 아래에서 그 사용자의 `CANCELING` 이용권 주문 존재 여부를 확인하고, 있으면 거부한다. 취소 시작과 새 이용권 주문이 직렬화되므로 취소 진행 중에는 새 승인이 생기지 않는다.
3. **외부 취소:** 카카오페이 cancel(6-5절)을 호출한다.
   - 성공하면 `CANCELED`로 전환한다.
   - 결과 불명(타임아웃·5xx)이면 `CANCELING`으로 두고 복구 대상으로 넘긴다. 조회 결과 `CANCEL_PAYMENT`이면 `CANCELED`, `SUCCESS_PAYMENT`이면 외부 취소를 다시 호출한다(복구 실행 참고).
   - 4xx이면 즉시 주문 조회를 한다. `CANCEL_PAYMENT`이면 `CANCELED`(`-785` 중복 요청 등), `SUCCESS_PAYMENT`이면 `NEEDS_REVIEW`로 둔다. 회수를 자동으로 되돌리면 그 사이의 잔액 변동과 충돌할 수 있으므로 결제사 상태를 확인한 뒤 수동으로 처리한다.
4. **`NEEDS_REVIEW` 수동 복구 기준:** 주문·원장·부여 이력을 삭제하지 않고 보존한다. 결제가 살아 있는데 회수만 된 충전은 같은 금액을 다시 적립하고, 이용권은 회수했던 30일을 현재 만료일 뒤에 다시 부여한다(`max(now, expires_at) + 30일`). 승인된 다른 이용권과 기간이 겹치거나 사라지지 않는다.
