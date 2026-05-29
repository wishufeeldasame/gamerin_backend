# 다이렉트 메시지 수정/삭제/대화방 나가기 기능 명세서

## 목적

기존 1:1 다이렉트 메시지 기능에 아래 3가지 기능을 추가하기 위한 백엔드/프론트 연동 명세입니다.

- 메시지 수정
- 메시지 삭제
- 대화방 나가기 또는 개인 사용자 기준 대화방 삭제

이 명세는 서버에 전체 대화 데이터는 유지하면서, 사용자별로 보이는 상태를 제어하는 방향을 기준으로 합니다.

## 공통 규칙

- API Prefix: `/api/v1/messages`
- 모든 API는 인증 필요: `Authorization: Bearer {accessToken}`
- 본인이 참여 중인 대화방만 접근 가능
- 삭제된 대화방 참여자는 해당 대화방을 조회하거나 메시지를 보낼 수 없음
- 메시지 수정/삭제는 기본적으로 작성자 본인만 가능
- 응답 형식은 기존 `ApiResponse` 형식을 유지

```json
{
  "success": true,
  "data": {}
}
```

## 1. 메시지 수정 API

### 기능 설명

사용자가 자신이 보낸 텍스트 메시지를 수정합니다.

수정 대상은 메시지의 `content`입니다. 첨부파일, 공유 게시글, 보낸 사람, 생성 시간은 수정하지 않습니다.

### Endpoint

```http
PATCH /api/v1/messages/conversations/{conversationId}/messages/{messageId}
Authorization: Bearer {accessToken}
Content-Type: application/json
```

### Request Body

```json
{
  "content": "수정된 메시지 내용"
}
```

### Request TypeScript

```ts
type UpdateMessageRequest = {
  content: string;
};
```

### Validation

- `content`는 필수
- trim 후 빈 문자열이면 실패
- 최대 2000자
- `conversationId`에 본인이 참여 중이어야 함
- `messageId`가 해당 대화방에 속해야 함
- 메시지 작성자 본인만 수정 가능
- 삭제된 메시지는 수정 불가

### Success Response

```json
{
  "success": true,
  "data": {
    "id": "message-uuid",
    "senderId": "me",
    "text": "수정된 메시지 내용",
    "createdAt": "2026-05-29T14:10:00+09:00",
    "editedAt": "2026-05-29T14:12:00+09:00",
    "read": false,
    "deliveryStatus": "sent",
    "attachments": [],
    "sharedPost": null
  }
}
```

### Response DTO 변경

기존 `MessageResponse`에 수정 여부 표시를 위해 `editedAt`을 추가하는 것을 권장합니다.

Before:

```ts
type MessageResponse = {
  id: string;
  senderId: "me" | string;
  text: string;
  createdAt: string;
  read: boolean;
  deliveryStatus: "sent";
  attachments: MessageAttachmentResponse[];
  sharedPost: SharedPostPreviewResponse | null;
};
```

After:

```ts
type MessageResponse = {
  id: string;
  senderId: "me" | string;
  text: string;
  createdAt: string;
  editedAt: string | null;
  read: boolean;
  deliveryStatus: "sent";
  attachments: MessageAttachmentResponse[];
  sharedPost: SharedPostPreviewResponse | null;
};
```

### DB

기존 `direct_messages.edited_at` 컬럼을 사용합니다.

추가 컬럼은 필요 없습니다.

### 프론트 처리

- 내 메시지에만 수정 버튼 노출
- 수정 완료 후 응답으로 받은 `MessageResponse`로 기존 메시지 교체
- `editedAt !== null`이면 "수정됨" 표시 가능

## 2. 메시지 삭제 API

### 기능 설명

사용자가 자신이 보낸 메시지를 삭제합니다.

이 명세의 삭제는 "모두에게 삭제"에 가깝습니다. 즉 메시지 작성자가 삭제하면 상대방에게도 해당 메시지가 더 이상 보이지 않습니다.

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
- 이미 삭제된 메시지는 성공 처리 또는 404 처리 중 하나를 선택해야 함
- 권장: 이미 삭제된 메시지는 404

### Success Response

```json
{
  "success": true,
  "data": null
}
```

### DB

기존 `direct_messages.deleted_at` 컬럼을 사용합니다.

첨부파일 row는 그대로 두되, 메시지 조회 쿼리에서 `direct_messages.deleted_at is null` 조건으로 제외합니다.

물리 파일 삭제는 선택사항입니다.

권장 정책:

- MVP: 파일은 유지
- 운영 보강: 메시지 삭제 시 첨부파일도 스토리지에서 삭제하고 attachment row도 soft delete 또는 hard delete

### 프론트 처리

- 내 메시지에만 삭제 버튼 노출
- 삭제 성공 후 화면에서 해당 메시지를 제거
- 대화방 목록 preview가 삭제된 메시지를 가리키면, 다음 최신 메시지로 갱신하기 위해 대화방 목록 재조회 권장

## 3. 대화방 나가기 API

### 기능 설명

사용자 개인 기준으로 대화방을 삭제합니다.

중요한 점은 서버에서 대화방 자체와 메시지는 삭제하지 않는다는 것입니다. 나간 사용자에게만 해당 대화방이 목록에서 사라집니다.

상대방은 기존 대화방과 메시지를 계속 볼 수 있습니다.

### Endpoint

```http
DELETE /api/v1/messages/conversations/{conversationId}
Authorization: Bearer {accessToken}
```

### 동작 정책

- `message_participants.deleted_at`에 현재 시간을 저장
- `message_conversations` row는 유지
- `direct_messages` row는 유지
- `direct_message_attachments` row는 유지
- 대화방 목록 조회 시 `message_participants.deleted_at is null`인 참여자만 반환
- 나간 사용자는 해당 대화방 상세 조회/메시지 전송/읽음 처리 불가

### Success Response

```json
{
  "success": true,
  "data": null
}
```

### 재대화 정책

대화방을 나간 사용자가 같은 상대에게 다시 메시지를 보내는 경우 정책을 정해야 합니다.

권장 정책은 기존 대화방 재활성화입니다.

- 기존 `direct_key`로 대화방을 찾음
- 나간 사용자의 `message_participants.deleted_at`을 `null`로 복구
- `joined_at`을 현재 시간으로 갱신
- `last_read_at`을 현재 시간으로 갱신
- 기존 메시지를 다시 보여줄지 여부는 서비스 정책에 따라 결정

MVP 권장:

- 재진입 시 기존 메시지도 다시 보이게 함
- 이유: DB 구조 변경이 적고, 1:1 DM의 연속성이 유지됨

개인 삭제에 더 가까운 UX를 원하면 추가 컬럼이 필요합니다.

```sql
ALTER TABLE message_participants
ADD COLUMN hidden_before TIMESTAMPTZ;
```

이 경우 대화방 나가기 시 `hidden_before = now()`로 저장하고, 재진입 후에는 `hidden_before` 이후 메시지만 보여줄 수 있습니다.

단, 지금 단계에서는 DB 수정이 늘어나므로 MVP에서는 `deleted_at`만 사용하는 방식을 권장합니다.

### 프론트 처리

- 대화방 목록에서 "삭제" 또는 "나가기" 메뉴 제공
- 성공 후 해당 대화방을 로컬 목록에서 제거
- 상세 화면에서 삭제했다면 DM 목록 화면으로 이동
- 상대방 화면에는 변화 없음

## 백엔드 구현 가이드

### 새로 추가할 Request DTO

```java
package com.gamerin.backend.domain.message.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMessageRequest(
        @NotBlank
        @Size(max = 2000)
        String content
) {
}
```

### Controller 추가 API

```java
@PatchMapping("/conversations/{conversationId}/messages/{messageId}")
public ApiResponse<MessageResponse> updateMessage(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @PathVariable UUID conversationId,
        @PathVariable UUID messageId,
        @Valid @RequestBody UpdateMessageRequest request
) {
    return ApiResponse.ok(messageService.updateMessage(principal, conversationId, messageId, request));
}

@DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
public ApiResponse<Void> deleteMessage(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @PathVariable UUID conversationId,
        @PathVariable UUID messageId
) {
    messageService.deleteMessage(principal, conversationId, messageId);
    return ApiResponse.ok(null);
}

@DeleteMapping("/conversations/{conversationId}")
public ApiResponse<Void> leaveConversation(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @PathVariable UUID conversationId
) {
    messageService.leaveConversation(principal, conversationId);
    return ApiResponse.ok(null);
}
```

### Entity 메서드 권장

`DirectMessage`

```java
public void updateContent(String content) {
    this.content = content;
    this.editedAt = OffsetDateTime.now();
}

public void delete() {
    this.deletedAt = OffsetDateTime.now();
}

public boolean isSentBy(UUID userId) {
    return sender != null && sender.getId().equals(userId);
}
```

`MessageParticipant`

```java
public void leave() {
    this.deletedAt = OffsetDateTime.now();
}

public void reactivate() {
    this.deletedAt = null;
    this.joinedAt = OffsetDateTime.now();
    this.lastReadAt = OffsetDateTime.now();
}
```

### Repository 추가 조회

`DirectMessageRepository`

```java
Optional<DirectMessage> findByIdAndConversationIdAndDeletedAtIsNull(UUID id, UUID conversationId);
```

`MessageParticipantRepository`

```java
Optional<MessageParticipant> findByConversationIdAndUserId(UUID conversationId, UUID userId);
```

기존에는 active participant만 찾는 메서드가 있을 수 있습니다. 재활성화를 하려면 deleted 상태까지 포함해서 조회하는 메서드가 필요합니다.

## 예외 응답 권장

### 권한 없음

```json
{
  "success": false,
  "message": "Conversation access is denied.",
  "data": null
}
```

### 본인 메시지가 아님

```json
{
  "success": false,
  "message": "Only the sender can modify this message.",
  "data": null
}
```

### 메시지 없음

```json
{
  "success": false,
  "message": "Message not found.",
  "data": null
}
```

## 테스트 체크리스트

- 내 메시지 수정 성공
- 상대 메시지 수정 실패
- 빈 문자열 수정 실패
- 2000자 초과 수정 실패
- 내 메시지 삭제 성공
- 상대 메시지 삭제 실패
- 삭제된 메시지는 목록에서 제외
- 대화방 나가기 성공
- 나간 대화방은 내 목록에서 제외
- 나간 대화방 상세 조회 실패
- 나간 대화방 메시지 전송 실패
- 상대방은 대화방을 계속 조회 가능
- 같은 상대에게 다시 대화 시작 시 기존 대화방 재사용 또는 재활성화

## 프론트 Codex 작업 요약

- 내 메시지 메뉴에 `수정`, `삭제` 액션 추가
- 메시지 수정 시 PATCH API 호출 후 응답 메시지로 교체
- 메시지 삭제 시 DELETE API 호출 후 로컬 목록에서 제거
- 대화방 목록 메뉴에 `대화방 삭제` 또는 `대화방 나가기` 액션 추가
- 대화방 나가기 성공 시 목록에서 제거하고 상세 화면이면 목록 화면으로 이동
- `editedAt`이 있으면 메시지 옆에 `수정됨` 표시

