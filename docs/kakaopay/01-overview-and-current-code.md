# 카카오페이 설계 — 개요·현재 코드 출발점 (1~2절)

[← 카카오페이 설계 문서 목차](README.md)

## 1. 권장 방향

- **확정:** 마일리지 잔액·원장을 유지하고 무료 가상 충전을 카카오페이 결제로 교체한다. 멘토링 신청·환불·정산은 마일리지로 처리한다.
- **확정:** 결제와 멤버십을 기존 Spring Boot 애플리케이션 안의 별도 도메인 패키지로 분리한다. 카카오페이는 결제 도메인 안의 외부 연동 클라이언트로 둔다.
- **확정:** 멤버십·지갑은 멘토링 밖의 독립 페이지로 둔다. 멘토링에는 잔액 요약·충전 바로가기·수수료 할인 안내를 남긴다.
- 멤버십은 서비스 전체의 배지·게시물 수정과 멘토의 정산 수수료 감면을 묶는다. 멘토가 아닌 사용자도 가입할 수 있다.
- 멤버십은 확정 정책에 따라 **카카오페이 직접 결제**로 구매하고 `30일 이용권 · 자동 갱신 없음`으로 표시한다.

멤버십은 충전을 거치지 않고 직접 구매한다. 멘토링은 카카오페이로 충전한 마일리지를 사용한다. 독립 페이지와 기존 백엔드 안의 패키지 분리, 6절의 내부 API·결제 상태 계약까지 확정했다.

### 1-1. 멘토링 마일리지 유지 확정과 선택 근거

| 선택 | 이용 흐름 | 장점 | 추가 부담 |
|---|---|---|---|
| **선택: 마일리지 유지 + 카카오페이 충전** | 충전 → 마일리지로 신청 → 완료 후 멘토에게 마일리지 정산 | 기존 차감·환불·원장·정산 흐름 재사용, 잔액이 있으면 바로 신청 | 충전 단계와 잔액 관리 필요; 충전 결제 환불은 내부 마일리지 반환과 별도 설계 |
| 비교한 대안: 마일리지 제거 + 멘토링 직접 결제 | 프로그램 선택 → 카카오페이 결제 → 신청·진행 → 완료 후 정산 | 사전 충전·잔액 없이 건별 결제 | 결제 성공과 신청 확정 연결, 자리·중복 신청 처리, 승인 후 신청 실패 복구, 현금 결제 취소 및 멘토 정산 기록 재설계 |

**확정:** 마일리지를 유지한다. 현재 코드의 마일리지 차감·반환·정산 흐름을 활용하고 충전 진입점을 결제로 교체한다. 결제 목적은 마일리지 충전과 멤버십 이용권 구매로 구분한다.

기존 마일리지의 `ESCROW_HELD`는 서비스 내부 상태다. 카카오페이 승인과 멘토에게 지급하는 동작을 동일하게 취급하지 않는다. 이번 멘토 정산은 마일리지 적립이며 실제 현금 출금은 후속 범위다.

카카오페이 결제 취소 API를 이용한 외부 결제 환불도 가능하지만 내부 마일리지 반환과 달리 외부 요청 실패·결과 불명 처리가 필요하다. [공식 결제 취소 문서](https://developers.kakaopay.com/docs/payment/online/cancellation)

## 2. 현재 코드에서 확인한 출발점

경로는 `capstone/` 기준이며, 백엔드 도메인 경로의 접두사는 `backend/src/main/java/com/gamerin/backend/`다.

| 영역 | 확인한 구현 | 설계에 미치는 영향 |
|---|---|---|
| 충전 | `domain/user/controller/MileageController.java`의 `POST /api/v1/mileage/charge`가 `MileageService.chargeMileage`로 결제 없이 적립(`referenceId = null`, 설명 `테스트용 가상 충전`). 프론트 `src/lib/mileage-api.ts`의 `chargeMileage`를 `mentoring/page.tsx`가 호출 | 일반 사용자의 가상 충전 경로 제거 필요 |
| 지갑·원장 | `domain/user/service/MileageService.java`에 `useMileage`, `addMileage`, 지갑 잠금과 거래 기록 존재. V25가 `mileage_wallets.balance >= 0`, `mentoring_applications.applied_mileage >= 0` CHECK 추가 | 결제 승인 후 기존 적립 로직 재사용 |
| 원장 제약 | V8의 `mileage_transactions.type`은 `VARCHAR(50)`(CHECK 없음), `reference_id`는 인덱스·UNIQUE 없음. `TransactionType`은 `CHARGE`, `MENTORING_PAY`, `MENTORING_REFUND`, `SETTLEMENT`, `WITHDRAW` | `CHARGE_CANCEL` 추가, `(type, reference_id)` 부분 UNIQUE(`type IN ('CHARGE','CHARGE_CANCEL')`) 신규 필요 |
| 화면 | `frontend/src/app/(app)/mentoring/page.tsx`의 `내 멘토링` 탭에 잔액·충전·거래 내역 결합(`chargeMileage` 호출) | 실제 `/mentoring/mileage` 하위 경로가 아니라 한 페이지의 탭 |
| 메뉴 | `frontend/src/app/home/components/Sidebar.tsx`에서 데스크톱과 모바일이 5개 `menuItems`(홈·북마크·메시지·멘토링·프로필) 공유, 모바일은 `grid-cols-5` | 데스크톱 메뉴 추가가 모바일 탭 증가로 이어지지 않도록 구분 필요 |
| 정산 | 멘토 전액 지급 경로가 **세 곳**: `MentoringService.completeMentoring`(수동 완료), `SettlementProcessor.processSingleSettlement`(7일 경과 자동, `REQUIRES_NEW`), `domain/admin/service/AdminMentoringService.forceSettle`(관리자 강제 정산, `POST /api/v1/admin/mentoring/applications/{id}/force-settle`) | 세 경로 모두 같은 수수료 규칙·`fee_amount` 기록 적용 필요 |
| 환불 | `MentoringService`의 취소·거절과 `AdminMentoringService.forceRefund`가 `appliedMileage` 전액 반환 | 수수료 도입 후에도 멘티 전액 반환 유지 |
| 신청 원장 | `MentoringService.applyToProgram`의 `MENTORING_PAY.referenceId`가 `null`이며 차감이 신청 저장보다 먼저 실행 | 신청 저장 후 `referenceId = application.id`로 연결하도록 순서 보완 필요 |
| 배지 | `domain/user/entity/UserProfile.java`의 `verifiedBadge`. 응답 필드는 프로필(`DetailedUserProfileResponse`, `SimpleUserProfileResponse`), 게시물·댓글(`PostCardResponse`, `PostDetailResponse`, `CommentResponse`의 `authorVerifiedBadge`), 팔로우 목록·알림 actor에 존재. 프론트는 테스트 fixture에만 등장하고 실제 화면 표시 없음 | 멤버십 배지 필드를 별도로 추가하고 프론트 표시 컴포넌트 신규 필요 |
| 게시물 | `domain/post/controller/PostController.java`에 생성·조회·삭제·좋아요·리포스트·북마크·공유·댓글은 있으나 수정 매핑 없음 | 신규 수정 API·정책 필요 |
| 신고·제재 | V24의 `reports.target_snippet`(신고 시점 스냅샷), `report_counts.is_hidden`(자동 숨김), `user_penalties`(제재 이력). 정지 제재 시 `UserPenaltyService`가 `User.status`를 `SUSPENDED`로 바꾸고, `global/security/filter/UserSuspensionFilter`가 인증 API 전체에 403을 반환한다. 경고(`WARNING`)는 상태를 바꾸지 않는다 | 수정 후 신고 스냅샷 유지; 숨김 게시물 수정 거부; 정지 사용자 결제는 기존 필터로 거부되어 별도 검사 불필요(확정) |
| 시스템 설정 | V24의 `system_configs`와 `AdminSettingsService`(관리자 키-값 설정) | 수수료율·이용권 가격은 확정 정책상 고정값이므로 이번 범위에서 설정화하지 않음 |
| 외부 연동·설정 | `RestClient` 기반 클라이언트(`RiotApiClient`, `PubgApiClient`, `R6DataStatsClient`, `OpenAiModerationClient`), `app.frontend.base-url`(운영 `FRONTEND_BASE_URL`, Compose 주입됨), `@EnableScheduling`과 기존 `@Scheduled` 작업 | `KakaoPayClient`는 같은 RestClient 패턴, 결과 URL은 `app.frontend.base-url` 재사용, 복구 스케줄러는 기존 스케줄링 사용 |
| 인증 복구 | `frontend/src/app/context/AuthContext.tsx`가 저장 사용자가 있으면 refresh 후 사용자 검증, `(app)/layout.tsx`가 완료까지 대기 | 복귀 기반은 있으나 저장 사용자 부재·재로그인 흐름 추가 검증 필요 |
| 마이그레이션 | 현재 최고 버전 **V29**(`develop` 동일). V20·V22·V26·V28은 홀수 규칙 등으로 비어 있음 | 새 migration은 **V31부터 홀수**(V31, V33, …). 구현 직전 `develop`과 열린 PR의 최대 버전을 다시 확인 |
