# 프론트엔드 연동 명세서: 멘토링 및 마일리지 시스템

이 문서는 `GamerIN Mentoring` 시스템의 프론트엔드 연동을 위한 가이드입니다. 멘토링 신청부터 완료까지의 상태 변화와 이에 따른 마일리지(결제/환불/정산) 흐름을 상세히 설명합니다.

---

## 1. 핵심 상태값 정의 (State Flow)

멘토링은 **신청 상태(ApplicationStatus)**와 **결제 상태(PaymentStatus)** 두 가지 축으로 관리됩니다.

### 1-1. ApplicationStatus (멘토링 진행 상태)
*   `APPLIED`: 멘티가 처음 신청한 상태 (수락 대기 중)
*   `ACCEPTED`: 멘토가 수락한 상태 (시작 전)
*   `REJECTED`: 멘토가 거절한 상태 (종료)
*   `CANCELLED`: 멘티가 스스로 취소한 상태 (수락 전, 종료)
*   `ONGOING`: 멘토가 멘토링 시작을 누른 상태 (진행 중)
*   `FINISHED`: 멘토가 수업 완료를 보고한 상태 (정산 대기)
*   `COMPLETED`: 멘티가 최종 완료를 확정한 상태 (종료, 정산 완료) 

### 1-2. PaymentStatus (마일리지 결제 상태)
*   `PENDING`: 결제 대기 (현재는 사용하지 않음)
*   `ESCROW_HELD`: 결제 금액이 플랫폼에 묶여있는 상태 (신청 시)
*   `REFUNDED`: 결제 금액이 멘티에게 환불된 상태 (거절/취소 시)
*   `SETTLED`: 결제 금액이 멘토에게 정산된 상태 (완료 확정 시)

---

## 2. 멘토링 & 마일리지 연동 흐름도 (User Flow)

### 🟢 멘토링 신청 시나리오 (결제)
1.  **행동**: 멘티가 `POST /api/v1/mentoring/applications` 호출
2.  **결과**: 멘티의 마일리지 지갑에서 프로그램 가격만큼 금액 차감 (`MENTORING_PAY`)
3.  **상태**: `APPLIED` / `ESCROW_HELD`

### 🔵 멘티의 신청 취소 (환불)
1.  **조건**: 수락 전(`APPLIED`) 상태에서만 가능
2.  **행동**: 멘티가 `PATCH /api/v1/mentoring/applications/{id}/cancel` 호출
3.  **결과**: 차감되었던 금액이 멘티 지갑으로 100% 반환 (`MENTORING_REFUND`)
4.  **상태**: `CANCELLED` / `REFUNDED`

### 🟡 멘토의 거절 (환불)
1.  **행동**: 멘토가 `PATCH /api/v1/mentoring/applications/{id}/reject` 호출
2.  **결과**: 차감되었던 금액이 멘티 지갑으로 100% 반환 (`MENTORING_REFUND`)
3.  **상태**: `REJECTED` / `REFUNDED`

### 🟠 진행 및 완료 확정 (정산)
1.  **진행**: `ACCEPTED` -> `ONGOING` (멘토가 시작) -> `FINISHED` (멘토가 수업 완료 보고)
    *   *이 과정에서는 마일리지 변동 없음.*
2.  **완료 확정 행동**: 멘티가 `PATCH /api/v1/mentoring/applications/{id}/complete` 호출
3.  **결과**: 플랫폼에 묶여있던(`ESCROW_HELD`) 금액이 **멘토의 지갑**으로 이체 (`SETTLEMENT`)
4.  **상태**: `COMPLETED` / `SETTLED`
    *   *참고: `FINISHED` 상태 후 7일이 지나면 멘티가 확정하지 않아도 자동으로 정산됩니다.*

---

## 3. 마일리지 API 명세

마일리지 내역은 프론트엔드의 **'마이페이지'** 또는 **'결제/정산 내역'** 메뉴에 사용됩니다. 모든 API는 인증 헤더(`Authorization: Bearer {token}`)가 필요합니다.

### 3-1. 내 마일리지 잔액 조회 (상단 표시용)
*   **Method**: `GET /api/v1/mileage/me/balance`
*   **Response**:
    ```json
    {
      "success": true,
      "data": {
        "currentBalance": 50000
      }
    }
    ```

### 3-2. 내 마일리지 거래 내역 조회 (리스트용 - 페이징)
*   **Method**: `GET /api/v1/mileage/me/transactions?page=0&size=10`
*   **Response**:
    ```json
    {
      "success": true,
      "data": {
        "content": [
          {
            "id": "uuid",
            "amount": -5000,
            "balanceAfter": 45000,
            "type": "MENTORING_PAY",
            "typeDescription": "멘토링 결제",
            "description": "멘토링 신청 결제: 롤 마스터 달성반",
            "createdAt": "2026-05-24T14:30:00Z"
          },
          {
            "id": "uuid",
            "amount": 50000,
            "balanceAfter": 50000,
            "type": "CHARGE",
            "typeDescription": "충전",
            "description": "테스트용 가상 충전",
            "createdAt": "2026-05-24T14:20:00Z"
          }
        ],
        "pageable": { ... },
        "totalElements": 2
      }
    }
    ```

### 3-3. 가상 충전 (테스트용)
*   **Method**: `POST /api/v1/mileage/charge`
*   **Request**:
    ```json
    {
      "amount": 10000
    }
    ```
*   **Response**: 변경된 잔액 정보 반환 (`currentBalance`)

---

## 4. 프론트엔드 작업 시 유의사항
1.  **결제 차단**: `POST /api/v1/mentoring/applications` 호출 시 잔액이 부족하면 `400 Bad Request` 에러(메시지: "마일리지가 부족합니다.")가 발생합니다. 프론트에서 이를 캐치하여 **"충전 페이지로 이동하시겠습니까?"** 모달을 띄워주는 것이 좋습니다.
2.  **버튼 활성화/비활성화**: 멘토링 상세 페이지에서 `ApplicationStatus`에 따라 적절한 버튼만 노출해야 합니다.
    *   `APPLIED` 상태: 멘티에게는 [신청 취소], 멘토에게는 [수락] / [거절] 버튼 노출
    *   `FINISHED` 상태: 멘티에게는 [완료 확정] 버튼 노출
3.  **트랜잭션 유형 표시**: `typeDescription` (충전, 멘토링 결제, 멘토링 환불, 정산 수입 등)을 화면에 직접 뿌려주면 사용자가 내역을 쉽게 이해할 수 있습니다. 금액이 음수(`amount < 0`)일 경우 빨간색/파란색 등 색상을 다르게 처리해 주세요.