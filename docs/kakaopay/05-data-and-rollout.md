# 카카오페이 설계 — 데이터·구현 순서·검증 (7~8절)

[← 카카오페이 설계 문서 목차](README.md)

## 7. 데이터·저장소별 구현 범위

정책은 모두 확정했다. 아래 정보·제약을 기준으로 SQL을 작성한다. 새 migration은 V31부터 홀수 번호를 사용하며, 단계별 PR로 나누면 PR마다 번호를 다시 확인한다.

| 데이터 | 필요한 정보·제약 |
|---|---|
| `payments` | 구매자, provider, 상품 코드·가격 스냅샷, 목적(`MILEAGE_CHARGE`/`MEMBERSHIP_PASS`), 주문 ID(= `partner_order_id`), CID/TID, 승인·취소 AID, 결제 수단(CARD/MONEY), 상태(6-6절 9개 값 CHECK), `status_changed_at`(처리권·결과 불명·만료 판정 기준), 결제창 URL 2개(`READY` 재응답용, 로그 미기록), ready/승인/취소 시각; TID UNIQUE(NULL 허용), `(buyer_id, request_id)` UNIQUE, 스케줄러용 `(status, status_changed_at)` 인덱스, 결제 목록용 `(buyer_id, created_at, id)` 인덱스. 카드 상세·pg_token은 저장하지 않음 |
| 마일리지 원장 | `CHARGE`(+)·`CHARGE_CANCEL`(−)의 `reference_id = payment.id`; `UNIQUE (type, reference_id) WHERE type IN ('CHARGE','CHARGE_CANCEL')`로 결제당 적립·회수 각 1회. 다른 거래 종류의 referenceId와 충돌하지 않음 |
| `user_memberships` | 현재 이용 기간·상품 |
| 멤버십 부여 이력 | 결제 ID UNIQUE, 부여 기간·상품, 재처리·환불 추적 |
| `mentoring_applications` | `fee_rate_bp`, `fee_amount`; 새 신청 시 요율 저장, 정산 시 저장 요율 적용; 기존 신청의 별도 호환 처리 제외(기존 행이 있는 DB에도 적용되도록 `fee_rate_bp`는 기본값 1000으로 추가) |
| 게시물 수정 제한 | 수정 횟수 정보와 원자적 갱신으로 최대 1회 제한; 수정 전 본문·별도 이력 테이블 없음 |

수수료 수익은 정산 완료 건의 fee_amount로, 멤버십 매출은 결제·취소 기록으로 구분해 집계한다. 충전액 전체를 즉시 플랫폼 수익으로 보지 않는다.

- **backend:** payment·membership 도메인, 무료 충전 제거, 정산 공통화·신청 참조·요율 스냅샷, 배지 응답, 본문 수정·횟수 제한·검열·멘션 처리. 기존 RestClient 패턴·타임아웃을 참고하되 결제 결과 불명은 별도 처리한다. `TransactionType.WITHDRAW`의 `+ 출금` 표기 오류는 별도 소규모 정리 대상이며 출금 구현을 이번 범위에 포함하지 않는다.
- **frontend:** 독립 페이지 3개, 멘토링의 충전·전체 거래 내역 UI 이동, 메뉴·복귀 동선, API 모듈(결제 목록 포함), 지갑의 마일리지 내역·결제 내역 탭, 가입·만료·처리 중·실패·결과 불명 화면, 인증 복구 실패 시 로그인 안내(승인 재개 없음), 배지·수정 UI.
- **설정 키(backend):** `kakaopay.api.base-url`(기본 `https://open-api.kakaopay.com`), `kakaopay.api.secret-key`(`${KAKAOPAY_SECRET_KEY:}`), `kakaopay.cid`(`${KAKAOPAY_CID:}`, 비어 있으면 결제 준비 거부), `kakaopay.api.connect-timeout`(3s), `kakaopay.api.read-timeout`(15s), `kakaopay.cancel-enabled`(`${KAKAOPAY_CANCEL_ENABLED:false}`). 허용 CID는 설정된 `kakaopay.cid` 하나다. 공개 예제(`application-local.example.yaml`, docker `.env.example`)에는 Secret key placeholder와 CID `TC0ONETIME`을 적는다. 결과 URL은 기존 `app.frontend.base-url` + `/payments/result`를 사용한다.
- **docker:** `KAKAOPAY_SECRET_KEY`·`KAKAOPAY_CID`·`KAKAOPAY_CANCEL_ENABLED`를 backend에 전달하고(결과 URL 기준 도메인은 이미 주입되는 `FRONTEND_BASE_URL` 사용), `/payments/result` 쿼리(pg_token)가 nginx access log에 남지 않게 한다. 그 밖에는 공개 예제에는 placeholder만 둔다. 기존 `/api/` 프록시를 활용하고 복귀 도메인·쿠키·로그를 검증한다. 실제 비밀 파일은 읽거나 수정하지 않는다. 다른 외부 API가 동작한다고 카카오페이 통신 성공을 단정하지 않는다.

## 8. 구현 순서·검증

각 단계는 자기 완료 기준을 통과하면 끝난다. 단계마다 migration 번호를 다시 확인한다.

### 1단계. 선행 작업 (결제와 무관, 바로 착수 가능)

신청 원장 순서·`referenceId` 보완(F2 앞부분), 요율 컬럼(F1)과 정산 3경로 공통화(F3·F4), 멤버십 상품 정의·저장·판정(M1·M2·M5).

- 멘토링 신청 저장 후 `MENTORING_PAY.referenceId = application.id`가 기록된다.
- 수동 완료·자동 정산·관리자 강제 정산이 같은 계산 메서드로 `fee = floor(applied × fee_rate_bp / 10000)`, 멘토 지급 `applied − fee`, `fee_amount`를 기록하고 세 경로의 금액이 일치한다. 자동 정산의 `REQUIRES_NEW` 경계가 유지된다.
- 취소·거절·`forceRefund`는 멘티에게 전액 반환하고 수수료를 계산하지 않는다.
- `isActive`·`findActiveUserIds`가 `expires_at > now`로 판정하고 목록 판정에서 N+1이 없다.

### 2단계. 충전 전환

상품 목록·주문·승인·조회·목록·복구(6-6절 상태·처리권·스케줄러)·재호출 응답·전액 결제 취소, 지갑·결과 페이지. 무료 충전 제거와 프론트 전환을 함께 반영한다.

- 상품 목록 응답과 ready 가격이 같은 서버 정의를 쓰고, 허용하지 않은 상품 코드는 400으로 거부된다.
- 승인 동시 요청·새로고침에도 한 번 적립, 타인 주문(403)·금액 변조 거부.
- 승인 응답 유실·DB 실패·취소 실패 후 복구하며 이중 결제·적립 없음.
- approve·cancel의 4xx(`-702` 재승인, `-785` 중복, `-780` 결과 미확인 상세 코드)를 즉시 `FAILED`로 닫지 않고 주문 조회 결과로 전이한다. `-702` 후 조회가 `SUCCESS_PAYMENT`면 적립·기간 부여가 정확히 한 번 일어난다.
- 요청 경로와 스케줄러가 같은 오래된 주문을 동시에 복구해도 외부 조회와 최종 반영은 한 작업자만 수행하고, 늦은 응답이 새 상태를 덮어쓰지 않는다.
- ready·approve·cancel 각각 DB 커밋 뒤 브라우저 응답을 유실시키고 재호출하면 6-6절 재호출 응답 표대로 동작하고 외부 호출·내부 반영이 중복되지 않는다.
- 결제 목록은 본인 주문만, 6-6절 대상 상태만 커서 순서대로 반환한다.
- 외부 이동 후 인증 복원·재로그인·계정 전환과 PC/모바일 복귀 처리.
- PC 팝업: 팝업 차단 안내, 부모 창으로의 결과 전달, 부모 창 부재 시 팝업 내 승인 폴백, 팝업 수동 종료 처리, 부모·팝업 중복 approve의 단일 반영.
- ready 응답 유실 시 주문 실패 처리, 15분 경과 미승인 주문의 만료 처리와 만료 주문 approve 거부.
- 모바일 refresh 실패·저장 사용자 부재·OAuth 재로그인·다른 계정 로그인에서 approve를 호출하지 않고, 미승인 주문은 만료로 정리되며, 승인됐거나 결과 불명인 주문에 새 결제를 유도하지 않는다.
- 충전 결제 취소: 잔액이 충전액 이상일 때만 `CHARGE_CANCEL`로 한 번 회수하고, 부족하면 409로 거부한다.
- 결제 취소는 소유자 본인·`cancel-enabled = true`·주문 CID와 설정 CID 일치일 때만 동작하고 그 외에는 거부한다.
- 사용자가 돌아오지 않아도 스케줄러가 `CREATED`·`READY`·`APPROVING`·`CANCELING` 주문을 6-6절 규칙대로 종료 상태로 정리한다.
- 사용자 환불 화면·부분 환불은 구현하지 않는다.
- 무료 충전 API가 제거되고 프론트가 이를 호출하지 않는다.

### 3단계. 멤버십

30일 이용권 구매·직접 재구매(M3·M4), 결제 취소 회수(M8), 내 멤버십 조회(M7), 멤버십 페이지, 배지(B1~B5).

- 중복 기간 연장 방지, 직접 재구매·만료·구매 실패와 결제 취소에 따른 기간 회수. 취소 대상 외 기존 이용 기간은 보존한다.
- 멤버십 행이 없는 사용자의 서로 다른 이용권 결제 두 건을 동시에 반영하면 부여 이력 두 건과 총 60일이 저장된다(PostgreSQL).
- 이용권 취소 시작 시 같은 사용자의 미완료 이용권 주문이 있으면 거부되고, `CANCELING` 중 새 이용권 ready가 거부된다. 외부 취소 성공·확정 실패·결과 불명 각각에서 기간이 중복되거나 사라지지 않는다.
- 후속 구매가 있는 이용권 취소는 409로 거부된다.
- 배지가 B1·B2 응답 전체에 현재 멤버십 기준으로 내려가고, 프론트 공통 컴포넌트가 `멤버십 회원` 접근성 이름으로 표시한다.

### 4단계. 수수료 요율 적용

신청 시 멘토 멤버십 판정으로 요율 저장(F2 뒷부분), 정산 표시(F5).

- 신청 시점에 멘토가 멤버십이면 500, 아니면 1000이 저장되고, 진행 중 가입·만료와 관계없이 해당 신청은 저장 요율로 정산된다.
- 멘토 화면에 적용 요율·수수료·수령액이 표시되고 멘티 가격은 그대로다.

### 5단계. 게시물 수정

본문·권한·시간·1회 제한·검열·멘션·동시성, 피드·상세 반영(E1~E9).

- 수정 소유권·시간·횟수·멤버십·숨김 여부를 서버 검증하고 검열을 재적용한다. 실패·무변경 요청은 횟수를 차감하지 않는다.
- 동시 수정 요청에도 최대 1회만 저장된다.
- 새로 추가된 멘션만 알림을 보내고, 수정 전 본문 보관·이력 조회·`수정됨` 표시 없이 최신 본문만 반영된다.

### 테스트 방식

백엔드는 카카오 클라이언트 mock 테스트와 테스트 전용 PostgreSQL의 중복·동시성 제약 검증을 수행한다. PostgreSQL 동시성 테스트는 기존 방식과 같이 `PAYMENT_POSTGRES_TEST_URL`·`PAYMENT_POSTGRES_TEST_USERNAME`·`PAYMENT_POSTGRES_TEST_PASSWORD`가 있을 때만 활성화하며(`@EnabledIfEnvironmentVariable`), 건너뛴 경우 통과로 보고하지 않는다. 결제·멤버십·게시물 수정의 동시성 테스트가 이 변수를 공유한다. 프론트는 API·결과 페이지 테스트와 화면 동선을 검증한다. 카카오페이 연동 결제 확인은 개발자센터 설정 후 별도로 수행한다.
