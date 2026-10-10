# 카카오페이 설계 — 백엔드 작업 분배 (11절)

[← 카카오페이 설계 문서 목차](README.md)

## 11. 작업 분배

작성일: 2026-10-10 · 통합 브랜치: `feature/kakaopay`

도메인 경계(`payment` / `membership` / 기존 `mentoring`·`post`)를 따라 나눈다. 각 작업 브랜치는 `feature/kakaopay`에서 따고 PR도 `feature/kakaopay`로 보낸다. 작업 ID는 [06 문서](06-membership-tasks.md)와 [04 문서](04-kakaopay-integration.md)를 따른다.

### 11-1. 담당

| 담당 | 영역 | 범위 |
|---|---|---|
| **A 장호** (`wishufeeldasame`) | `domain/payment` + 충전 | `KakaoPayClient`·설정 키, `payments`, 상품 목록·ready·approve·조회·목록·취소 API, 상태 9개·처리권·오류 분류, 복구 스케줄러, 재호출 응답, `CHARGE`·`CHARGE_CANCEL` 원장·부분 UNIQUE, 무료 충전 제거, 이용권 취소 중 ready 거부, docker 환경변수 |
| **B 경식** (`WhiteB1ossom`) | `domain/membership` + 배지 | M1·M2·M5(먼저), M3·M4(최초 행 잠금)·M7·M8(회수 로직)·M6·M10, 배지 B1~B5 |
| **C 상혁** (`lsh050121`) | 기존 `mentoring`·`post` | F1·F2·F3·F4, 요율 스냅샷·F5, 게시물 수정 E1~E8 |

A가 가장 무겁다(동시성·외부 연동·복구). 일정이 밀리면 읽기 전용인 결제 목록·상품 목록 API를 B로 넘긴다.

### 11-2. 이슈·브랜치

| ID | 담당 | 작업 | 이슈 | 브랜치 | migration |
|---|---|---|---|---|---|
| A1 | 장호 | 결제 코어: 클라이언트·`payments`·상품 목록·ready·approve·단건 조회 | [#85](https://github.com/wishufeeldasame/gamerin_backend/issues/85) | `feature/#85_kakaopay_payment_core` | V35 |
| A2 | 장호 | 결제 복구·재호출·취소·목록과 충전 전환 | [#86](https://github.com/wishufeeldasame/gamerin_backend/issues/86) | `feature/#86_kakaopay_payment_recovery` | — |
| B1 | 경식 | 멤버십 코어: 인터페이스 스텁·상품·저장·판정 | [#87](https://github.com/wishufeeldasame/gamerin_backend/issues/87) | `feature/#87_membership_core` | V33 |
| B2 | 경식 | 멤버십 배지 | [#88](https://github.com/wishufeeldasame/gamerin_backend/issues/88) | `feature/#88_membership_badge` | — |
| B3 | 경식 | 이용권 기간 부여·회수·내 멤버십 조회 | [#89](https://github.com/wishufeeldasame/gamerin_backend/issues/89) | `feature/#89_membership_grant` | V37 |
| C1 | 상혁 | 멘토링 정산 공통화·신청 원장 연결 | [#90](https://github.com/wishufeeldasame/gamerin_backend/issues/90) | `feature/#90_mentoring_settlement_fee` | V31 |
| C2 | 상혁 | 수수료 요율 스냅샷·정산 표시 | [#91](https://github.com/wishufeeldasame/gamerin_backend/issues/91) | `feature/#91_mentoring_fee_snapshot` | — |
| C3 | 상혁 | 멤버십 게시물 수정 | [#92](https://github.com/wishufeeldasame/gamerin_backend/issues/92) | `feature/#92_post_edit` | V39 |

### 11-3. 진행 순서

| 시기 | 장호 | 경식 | 상혁 |
|---|---|---|---|
| **1차**(의존 없음) | A1 | **B1 인터페이스 스텁을 첫날 머지** → B1 → B2 | C1 |
| **2차** | A2 | B3(A1의 `payments`와 연결) | C2 → C3 |
| **통합** | A2·B3로 이용권 구매·취소를 끝까지 검증 | | |

C2·C3의 멤버십 판정은 B1의 `isActive`에 의존한다. B1 스텁(항상 `false`)이 먼저 머지되면 상혁은 기다리지 않는다. A1의 이용권 승인도 같은 스텁의 `grant`를 호출하고, B3가 실제 구현으로 바꾼다.

### 11-4. 첫날 합의할 인터페이스

1. **`MembershipService`(경식 제공):** `isActive(userId)`, `findActiveUserIds(ids)`, `grant(userId, paymentId)`, `revoke(paymentId)`. `grant`·`revoke`는 장호의 결제 트랜잭션 안에서 호출되므로 자체 `REQUIRES_NEW`를 쓰지 않는다.
2. **상품 정의 위치:** 충전 4종과 `MEMBERSHIP_30D`를 `domain/payment`의 상품 정의 하나로 모은다(가격 출처 단일화). 멤버십은 여기서 30일을 읽는다.
3. **이용권 결제 취소 분담:** 미완료 이용권 주문 검사·`CANCELING` 중 ready 거부는 장호(`payments` 조회), 기간 회수·마지막 `GRANTED` 판정은 경식(`revoke`).

### 11-5. 충돌 주의

- **Flyway 번호:** out-of-order가 꺼져 있으므로 번호 순서대로 머지돼야 한다. 예정 순서는 V31 상혁(요율 컬럼) → V33 경식(`user_memberships`) → V35 장호(`payments` + 원장 부분 UNIQUE) → V37 경식(`membership_grants`, `payments` FK) → V39 상혁(`posts.edit_count`)이다. 머지 순서가 바뀌면 PR 직전에 번호를 다시 매긴다.
- **B2와 C2·C3가 같은 파일을 고친다:** `PostCardResponse`·`PostDetailResponse`·`PostResponseAssembler`, `MentoringApplicationResponse` 등. B2를 먼저 머지하고 상혁이 그 위에 필드를 더한다.
- **PostgreSQL 동시성 테스트:** 세 명 모두 `PAYMENT_POSTGRES_TEST_*`를 쓴다. 같은 DB를 동시에 쓰면 서로 데이터를 지울 수 있으므로 사람별로 DB나 스키마를 나눈다.

### 11-6. 리뷰 짝

| 작성자 | 리뷰어 | 이유 |
|---|---|---|
| 장호 | 경식 | 결제 ↔ 멤버십 연결 지점 |
| 경식 | 상혁 | 배지·게시물 DTO 겹침 |
| 상혁 | 장호 | 정산·원장이 마일리지와 맞닿음 |
