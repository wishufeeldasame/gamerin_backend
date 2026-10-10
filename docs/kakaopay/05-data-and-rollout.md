# 카카오페이 설계 — 데이터·구현 순서·검증 (7~8절)

[← 카카오페이 설계 문서 목차](README.md)

## 7. 데이터·저장소별 구현 범위

정책은 모두 확정했다. 아래 정보·제약을 기준으로 SQL을 작성한다. 새 migration은 V31부터 홀수 번호를 사용하며, 단계별 PR로 나누면 PR마다 번호를 다시 확인한다.

| 데이터 | 필요한 정보·제약 |
|---|---|
| `payments` | 구매자, provider, 상품 코드·가격 스냅샷, 목적(`MILEAGE_CHARGE`/`MEMBERSHIP_PASS`), 주문 ID(= `partner_order_id`), CID/TID, 승인·취소 AID, 결제 수단(CARD/MONEY), 상태(6-6절 8개 값 CHECK), `status_changed_at`(결과 불명·만료 판정 기준), ready/승인/취소 시각; TID UNIQUE(NULL 허용), `(buyer_id, request_id)` UNIQUE, 스케줄러용 `(status, status_changed_at)` 인덱스. 카드 상세·pg_token은 저장하지 않음 |
| 마일리지 원장 | `CHARGE`(+)·`CHARGE_CANCEL`(−)의 `reference_id = payment.id`; `UNIQUE (type, reference_id) WHERE type IN ('CHARGE','CHARGE_CANCEL')`로 결제당 적립·회수 각 1회. 다른 거래 종류의 referenceId와 충돌하지 않음 |
| `user_memberships` | 현재 이용 기간·상품 |
| 멤버십 부여 이력 | 결제 ID UNIQUE, 부여 기간·상품, 재처리·환불 추적 |
| `mentoring_applications` | `fee_rate_bp`, `fee_amount`; 새 신청 시 요율 저장, 정산 시 저장 요율 적용; 기존 신청의 별도 호환 처리 제외(기존 행이 있는 DB에도 적용되도록 `fee_rate_bp`는 기본값 1000으로 추가) |
| 게시물 수정 제한 | 수정 횟수 정보와 원자적 갱신으로 최대 1회 제한; 수정 전 본문·별도 이력 테이블 없음 |

수수료 수익은 정산 완료 건의 fee_amount로, 멤버십 매출은 결제·취소 기록으로 구분해 집계한다. 충전액 전체를 즉시 플랫폼 수익으로 보지 않는다.

- **backend:** payment·membership 도메인, 무료 충전 제거, 정산 공통화·신청 참조·요율 스냅샷, 배지 응답, 본문 수정·횟수 제한·검열·멘션 처리. 기존 RestClient 패턴·타임아웃을 참고하되 결제 결과 불명은 별도 처리한다. `TransactionType.WITHDRAW`의 `+ 출금` 표기 오류는 별도 소규모 정리 대상이며 출금 구현을 이번 범위에 포함하지 않는다.
- **frontend:** 독립 페이지 3개, 멘토링의 충전·전체 거래 내역 UI 이동, 메뉴·복귀 동선, API 모듈, 가입·만료·처리 중·실패·결과 불명 화면, 배지·수정 UI.
- **설정 키(backend):** `kakaopay.api.base-url`(기본 `https://open-api.kakaopay.com`), `kakaopay.api.secret-key`(`${KAKAOPAY_SECRET_KEY:}`), `kakaopay.cid`(`${KAKAOPAY_CID:}`, 비어 있으면 결제 준비 거부), `kakaopay.api.connect-timeout`(3s), `kakaopay.api.read-timeout`(15s), `kakaopay.cancel-enabled`(`${KAKAOPAY_CANCEL_ENABLED:false}`). 허용 CID는 설정된 `kakaopay.cid` 하나다. 공개 예제(`application-local.example.yaml`, docker `.env.example`)에는 Secret key placeholder와 CID `TC0ONETIME`을 적는다. 결과 URL은 기존 `app.frontend.base-url` + `/payments/result`를 사용한다.
- **docker:** `KAKAOPAY_SECRET_KEY`·`KAKAOPAY_CID`·`KAKAOPAY_CANCEL_ENABLED`를 backend에 전달하고(결과 URL 기준 도메인은 이미 주입되는 `FRONTEND_BASE_URL` 사용), `/payments/result` 쿼리(pg_token)가 nginx access log에 남지 않게 한다. 그 밖에는 공개 예제에는 placeholder만 둔다. 기존 `/api/` 프록시를 활용하고 복귀 도메인·쿠키·로그를 검증한다. 실제 비밀 파일은 읽거나 수정하지 않는다. 다른 외부 API가 동작한다고 카카오페이 통신 성공을 단정하지 않는다.

## 8. 구현 순서·검증

1. **선행 작업(결제와 무관, 바로 착수 가능):** 신청 원장 순서·`referenceId` 보완(F2), 정산 3경로 공통화(F3), 멤버십 저장·판정(M1·M2·M5).
2. **충전 전환:** 주문·승인·조회·복구(6-6절 상태·스케줄러)·전액 결제 취소와 지갑·결과 페이지. 무료 충전 제거와 프론트 전환을 같은 배포 단위로 맞춘다.
3. **멤버십:** 30일 이용권 구매·직접 재구매, 기간·부여 이력·혜택 판정, 멤버십 페이지·배지.
4. **수수료:** 수동·자동·관리자 강제 정산 공통화, 신청 참조·요율 스냅샷, 정산 표시.
5. **게시물 수정:** 본문·권한·시간·1회 제한·검열·멘션·동시성, 피드·상세 반영.

완료 기준:

- 승인 동시 요청·새로고침에도 한 번 적립, 타인 주문·금액 변조 거부.
- 승인 응답 유실·DB 실패·취소 실패 후 복구하며 이중 결제·적립 없음.
- 외부 이동 후 인증 복원·재로그인·계정 전환과 PC/모바일 복귀 처리.
- PC 팝업: 팝업 차단 안내, 부모 창으로의 결과 전달, 부모 창 부재 시 팝업 내 승인 폴백, 팝업 수동 종료 처리, 부모·팝업 중복 approve의 단일 반영.
- ready 응답 유실 시 주문 실패 처리, 15분 경과 미승인 주문의 만료 처리와 만료 주문 approve 거부.
- 수동·자동·관리자 강제 정산 금액 일치, 신청 당시 요율 보존.
- 중복 기간 연장 방지, 직접 재구매·만료·구매 실패와 결제 취소에 따른 기간 회수. 취소 대상 외 기존 이용 기간은 보존한다.
- 전액 결제 취소와 잔액 회수는 한 번만 반영한다. 잔액이 충전액보다 적은 충전 취소와 후속 구매가 있는 이용권 취소는 거부하며 사용자 환불 화면·부분 환불은 구현하지 않는다.
- 결제 취소는 소유자 본인·`cancel-enabled = true`·주문 CID와 설정 CID 일치일 때만 동작하고 그 외에는 거부한다.
- 사용자가 돌아오지 않아도 스케줄러가 `CREATED`·`READY`·`APPROVING`·`CANCELING` 주문을 6-6절 규칙대로 종료 상태로 정리한다.
- 수정 소유권·시간·횟수·멤버십을 서버 검증하고 검열을 재적용한다. 수정 전 본문 보관·이력 조회·`수정됨` 표시가 없고 최신 본문만 반영되는지 확인한다.

백엔드는 카카오 클라이언트 mock 테스트와 테스트용 PostgreSQL의 중복·동시성 제약 검증을 수행한다. 프론트는 API·결과 페이지 테스트와 화면 동선을 검증한다. 카카오페이 연동 결제 확인은 개발자센터 설정 후 별도로 수행한다.
