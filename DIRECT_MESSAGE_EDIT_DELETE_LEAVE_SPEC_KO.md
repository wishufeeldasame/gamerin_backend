# 다이렉트 메시지 삭제/대화방 나가기 기능 명세서

## 목적

기존 1:1 다이렉트 메시지 기능에 아래 사용자 관리 기능을 추가하기 위한 백엔드/프론트 연동 명세입니다.

- 메시지 삭제
- 대화방 나가기 또는 개인 사용자 기준 대화방 삭제

메시지 수정 기능은 현재 지원하지 않습니다.

## 공통 규칙

- API Prefix: `/api/v1/messages`
- 모든 API는 인증 필요: `Authorization: Bearer {accessToken}`
- 본인이 참여 중인 대화방만 접근 가능
- 나간 사용자는 해당 대화방을 조회하거나 메시지를 보낼 수 없음
- 메시지 삭제는 작성자 본인만 가능
- 응답 형식은 기존 `ApiResponse` 형식을 유지

```json
{
  "success": true,
  "data": {}
}
```

## 1. 메시지 삭제 API

### 기능 설명

사용자가 자신이 보낸 메시지를 삭제합니다.

이 삭제는 "모두에게 삭제"에 가깝습니다. 메시지 작성자가 삭제하면 상대방에게도 해당 메시지가 더 이상 보이지 않습니다.

서버에서는 실제 row를 삭제하지 않고 `direct_messages.deleted_at`에 삭제 시간을 기록합니다.

### Endpoint

```http
DELETE /api/v1/messages/conversations/{conversationId}/messages/{messageId}
Authorization: Bearer {accessToken}
```

### Validation

- `conversationId`에 본인이 참여 중이어야 함
- `messageId`가 해당 대화방에 속해야 함
- 메시지 작성자 본인만 삭제 가능
- 삭제된 메시지는 조회 목록에서 제외

### Success Response

```json
{
  "success": true,
  "data": null
}
```

## 2. 대화방 나가기 API

### 기능 설명

사용자 개인 기준으로 대화방을 삭제합니다.

서버에서 대화방 자체와 메시지는 삭제하지 않습니다. 나간 사용자에게만 해당 대화방이 목록에서 사라지고, 상대방은 기존 대화방과 메시지를 계속 볼 수 있습니다.

### Endpoint

```http
DELETE /api/v1/messages/conversations/{conversationId}
Authorization: Bearer {accessToken}
```

### 동작 정책

- `message_participants.deleted_at`에 현재 시간을 저장
- `message_participants.cleared_at`에 현재 시간을 저장
- `message_conversations`, `direct_messages`, `direct_message_attachments` row는 유지
- 대화방 목록 조회 시 `message_participants.deleted_at is null`인 참여자만 반환
- 메시지 조회 시 `cleared_at` 이후 메시지만 반환
- 나간 사용자는 해당 대화방 상세 조회/메시지 전송/읽음 처리 불가

### 재대화 정책

- 나간 사용자가 직접 같은 상대와 대화방을 다시 열면 본인 participant만 재활성화
- 상대방이 단순히 대화방을 열거나 조회해도 나간 사용자는 재활성화되지 않음
- 상대방이 새 메시지를 보내면 나간 사용자의 participant를 incoming message 기준으로 재활성화
- 재활성화 후에도 `cleared_at`은 유지하여 나가기 이전 메시지는 숨김

### Success Response

```json
{
  "success": true,
  "data": null
}
```

## 프론트 처리

- 내 메시지에는 삭제 버튼만 노출
- 메시지 삭제 성공 후 화면에서 해당 메시지를 제거
- 대화방 나가기 성공 후 해당 대화방을 목록에서 제거하고 목록 화면으로 이동
- 대화방 목록 preview가 삭제된 메시지를 가리키면 대화방 목록 재조회 권장

## 테스트 범위

- 내 메시지 삭제 성공
- 상대 메시지 삭제 실패
- 삭제 후 목록에서 제외
- 대화방 나가기 성공
- 나간 대화방은 내 목록에서 제외
- 상대방은 대화방을 계속 조회 가능
- 나간 대화방 상세 조회 실패
- 나간 대화방 메시지 전송 실패
- 같은 상대에게 다시 대화 시작 시 본인 participant만 재활성화
- 상대가 새 메시지를 보내면 나간 사용자 participant가 incoming 기준으로 재활성화
