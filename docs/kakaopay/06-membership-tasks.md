# 카카오페이 설계 — 멤버십 파생 구현 항목 (10절)

[← 카카오페이 설계 문서 목차](README.md)

## 10. 확정 정책에서 파생되는 멤버십 구현 항목

확정 정책을 만족하기 위해 **추가로 만들어야 하는 기능**을 정리했다. 동작·제약은 확정이며, 클래스·경로·컬럼 이름만 구현 중 기존 코드 규칙에 맞춰 조정할 수 있다.

### 10-1. 멤버십 코어 (`domain/membership`)

| ID | 항목 | 근거 정책 | 내용 |
|---|---|---|---|
| M1 | 이용권 상품 정의 | 가격 4,900원·30일 | 서버 상수 `MEMBERSHIP_30D`(가격 4,900, 기간 30일). 결제 ready 시 이 정의로 금액을 결정하고 응답·화면 표시 가격도 같은 정의에서 내려준다. 설정 테이블·관리자 수정 기능은 만들지 않는다. |
| M2 | 이용 기간 저장 | 30일 이용권·직접 재구매 | `user_memberships(user_id PK, expires_at, updated_at)`. 사용자당 1행이며 상태 컬럼 없이 `expires_at > now`로 유효를 판정한다. |
| M3 | 부여 이력 | 중복 연장 방지·취소 회수 | `membership_grants(id, user_id, payment_id UNIQUE, granted_from, granted_until, status GRANTED/REVOKED, created_at)`. 결제 하나로 기간을 두 번 늘리지 못하게 막는다. |
| M4 | 기간 부여 | 미리 재구매 시 `max(now, expires_at) + 30일` | `user_memberships` 행을 비관적 잠금한 뒤 `granted_from = max(now, expires_at)`, `granted_until = granted_from + 30일`을 계산한다. 이력 저장과 결제 `APPROVED` 전환을 한 트랜잭션에서 처리한다. |
| M5 | 유효 판정 API(내부) | 배지·수정·수수료 모두 현재 상태 기준 | `MembershipService.isActive(userId)`와 목록용 `findActiveUserIds(Collection<UUID>)`. 피드·댓글 목록에서 작성자마다 조회하는 N+1을 막는다. |
| M6 | 만료 처리 | 만료 시 배지 제거·혜택 중단 | 만료 스케줄러는 만들지 않고 조회 시점에 `expires_at`으로 계산한다. 만료 알림은 정책에 없으므로 제외한다. |
| M7 | 내 멤버십 조회 | 가입자에게 상태·만료일 표시 | `GET /api/v1/memberships/me`: `active`, `expiresAt`, 상품 가격·기간, 혜택 요약 |
| M8 | 결제 취소 회수 | 해당 구매 기간만 회수 | 취소 대상 grant가 사용자의 **마지막 GRANTED 이력**일 때만 허용하고 `expires_at = granted_from`으로 되돌린 뒤 `REVOKED` 처리한다. 후속 구매가 있으면 거부한다. 이용 기간 중 게시물 수정·5% 요율 신청 여부는 검사하지 않는다. 이전 구매로 남은 기간은 그대로 보존된다. |
| M9 | 정지 사용자 결제 거부 | 정지 중 결제 불가 | 추가 구현 없음. `User.status = SUSPENDED`이면 기존 `UserSuspensionFilter`가 결제 API를 포함한 인증 API 전체에 403을 반환한다. 결제 API가 이 필터 뒤의 인증 경로에 있는지만 테스트로 확인한다. 경고만 받은 사용자는 결제할 수 있다. |
| M10 | 기간 상한 없음 | 미리 재구매 누적 | 누적 만료일 상한 검사를 두지 않는다. |

### 10-2. 배지

| ID | 항목 | 내용 |
|---|---|---|
| B1 | 기존 배지 필드가 있는 응답 | `membershipBadge`(작성자 문맥은 `authorMembershipBadge`)를 추가한다. 대상: `DetailedUserProfileResponse`, `SimpleUserProfileResponse`, `PostCardResponse`, `PostDetailResponse`, `CommentResponse`, `FollowUserResponse`, `NotificationActorResponse`. 조립 지점은 `UserService`, `PostResponseAssembler`, `FollowService`, `NotificationQueryService`. 기존 `verifiedBadge`는 그대로 둔다. |
| B2 | 배지 필드가 없는 사용자 노출 응답 | 새로 추가한다: 리포스트한 사용자(`ReposterInfoResponse`), DM 상대·대화방(`MessageRecipientResponse`, `ConversationResponse`의 참여자), 멘토링 멘토·멘티·리뷰 작성자(`MentorProfileResponse`, `MentoringProgramResponse`, `MentoringProgramDetailResponse`, `MentoringApplicationResponse`, `MentoringReviewResponse`), 로그인 사용자 본인(`MeResponse`). 검색 결과(`SearchOverviewResponse`)는 `SimpleUserProfileResponse`·`PostCardResponse`를 재사용하므로 B1 반영으로 함께 처리된다. |
| B3 | 일괄 판정 | 목록 응답은 M5의 `findActiveUserIds`로 한 번에 조회한다. 사용자마다 조회하는 N+1을 만들지 않는다. |
| B4 | 프론트 컴포넌트 | 작은 왕관 아이콘 공통 컴포넌트, `aria-label`/툴팁 `멤버십 회원`. 닉네임을 표시하는 공통 사용자 표시 요소에 넣어 모든 화면이 재사용하게 한다. |
| B5 | 제외 | 관리자 화면(`domain/admin`, 신고 관리 응답)은 사용자 화면이 아니므로 이번 노출 범위에서 제외한다. |

### 10-3. 게시물 수정

| ID | 항목 | 내용 |
|---|---|---|
| E1 | API | `PATCH /api/v1/posts/{postId}` `{ content }`. 미디어 필드는 받지 않는다. |
| E2 | 서버 검증 | 작성자 본인, 삭제되지 않음, 숨김(`report_counts.is_hidden`) 아님, `now - createdAt <= 1시간`, 요청 시점 멤버십 유효(M5), `edit_count = 0`. JWT나 프론트 버튼 노출 여부를 근거로 허용하지 않는다. |
| E3 | 스키마 | `posts.edit_count SMALLINT NOT NULL DEFAULT 0 CHECK (edit_count BETWEEN 0 AND 1)`. 수정 전 본문·이력 테이블은 없다. |
| E4 | 원자성 | `UPDATE … SET content = ?, edit_count = 1 WHERE id = ? AND edit_count = 0` 조건부 갱신 또는 행 잠금. 본문이 같으면 저장·차감하지 않고 그대로 반환한다. |
| E5 | 검열·보안 | 생성 때와 같은 텍스트 검사와 `ContentModerationService` 검열을 다시 적용한다. 실패하면 차감하지 않는다. |
| E6 | 해시태그·멘션 | `HashtagParser`/`MentionParser`로 다시 파싱해 관계를 새 본문 기준으로 바꾼다. 새로 추가된 멘션만 알림을 보낸다. 제거된 멘션의 기존 알림은 유지한다. |
| E7 | 반영 범위 | 피드·상세·DM 공유 미리보기(`SharedPostPreviewResponse`)가 최신 본문을 읽는지 확인한다. 신고의 `target_snippet`은 신고 시점 스냅샷이므로 갱신하지 않는다. |
| E8 | 수정 가능 정보 | 작성자 본인 응답에 `editableUntil`(createdAt + 1시간)과 `editCount`를 내려 프론트 버튼 노출에 쓴다. 다른 사용자에게는 `수정됨` 표시를 하지 않는다. |
| E9 | 프론트 | 본인 게시물 메뉴의 `수정` 진입, 텍스트 전용 편집 UI, 비가입자에게 `/membership` 안내, 시간·횟수 초과 오류 표시. |

### 10-4. 멘토 정산 수수료

| ID | 항목 | 내용 |
|---|---|---|
| F1 | 스키마 | `mentoring_applications.fee_rate_bp INT NOT NULL DEFAULT 1000 CHECK (fee_rate_bp BETWEEN 0 AND 10000)`, `fee_amount BIGINT NULL`(정산 시 기록). |
| F2 | 신청 시 스냅샷 | `applyToProgram`에서 멘토의 M5 판정으로 1000/500을 저장한다. 신청 저장 후 `MENTORING_PAY.referenceId = application.id`로 연결한다. |
| F3 | 정산 공통화 | `completeMentoring`, `SettlementProcessor.processSingleSettlement`, `AdminMentoringService.forceSettle`이 같은 계산 메서드를 쓴다. `fee = floor(applied × bp / 10000)`, 멘토 지급 = `applied - fee`, `fee_amount`를 저장한다. 자동 정산의 `REQUIRES_NEW` 경계는 유지한다. |
| F4 | 환불 | 취소·거절·`forceRefund`는 기존처럼 멘티에게 전액 반환하고 수수료는 계산하지 않는다. |
| F5 | 표시 | 멘토의 신청·정산 화면에 적용 요율·수수료·수령액을 표시한다. 비가입 멘토에게는 5% 혜택 안내와 `/membership` 링크를 보여준다. 멘티 화면 가격은 그대로다. |

### 10-5. 프론트 멤버십 화면

- `/membership`: 비가입자에게는 혜택 3종·4,900원·30일·자동 갱신 없음을, 가입자에게는 만료일과 미리 재구매 시 연장될 만료일을 보여준다.
- `src/lib/membership-api.ts`: M7 조회. 구매는 `payment-api.ts`의 ready에 `MEMBERSHIP_30D`를 전달한다.
- `/payments/result`: 멤버십 구매 성공 시 새 만료일을 표시하고, 배지가 바로 반영되도록 사용자 정보를 다시 조회한다.
- 진입점: 데스크톱 사이드바, 모바일 프로필·계정 메뉴, 게시물 수정 안내, 멘토 수수료 안내.

### 10-6. 2026-10-10 확정 결과

10절 작성 시 남겼던 결정 항목을 모두 확정했고 상단 확정 표에 반영했다.

| 항목 | 확정 |
|---|---|
| 이용권 결제 취소의 혜택 사용 판정 | 후속 구매 여부만 검사 (M8) |
| 정지 사용자 결제 | 거부, 기존 `SUSPENDED` 필터로 처리 (M9) |
| 누적 이용 기간 상한 | 없음 (M10) |
| 배지 노출 범위 | 사용자 프로필이 표시되는 모든 사용자 화면 (B1~B5) |
| 수정 시 제거된 멘션 알림 | 유지 (E6) |
| 숨김 게시물 수정 | 거부 (E2) |
