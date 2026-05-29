# 다이렉트 메시지 수정/삭제/대화방 나가기 개발 변경 가이드

## 목적

`DIRECT_MESSAGE_EDIT_DELETE_LEAVE_SPEC_KO.md` 명세대로 개발했을 때 백엔드 코드, DB, API 응답, 프론트 연동 흐름에서 어떤 부분이 바뀌는지 정리한 가이드입니다.

대상 기능은 아래 3가지입니다.

- 메시지 수정
- 메시지 삭제
- 대화방 나가기 또는 개인 사용자 기준 대화방 삭제

## 큰 방향

기존 메시지 기능은 메시지 생성, 조회, 읽음 처리, 첨부파일, 게시글 공유 중심입니다.

이번 개발을 하면 메시지를 보낸 뒤에도 사용자가 관리할 수 있는 기능이 추가됩니다.

- 내가 보낸 메시지를 수정할 수 있음
- 내가 보낸 메시지를 삭제할 수 있음
- 대화방을 서버 전체에서 지우지 않고 내 목록에서만 숨길 수 있음
- 상대방의 대화방과 메시지는 유지됨

## API 변경

### 새로 추가되는 API

```http
PATCH /api/v1/messages/conversations/{conversationId}/messages/{messageId}
```

내가 보낸 메시지의 텍스트를 수정합니다.

```http
DELETE /api/v1/messages/conversations/{conversationId}/messages/{messageId}
```

내가 보낸 메시지를 삭제합니다.

```http
DELETE /api/v1/messages/conversations/{conversationId}
```

내 기준으로 대화방을 나갑니다.

## 응답 DTO 변경

### MessageResponse

메시지가 수정됐는지 프론트에서 알 수 있도록 `editedAt` 필드가 추가됩니다.

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

프론트는 `editedAt !== null`이면 메시지 옆에 `수정됨` 표시를 할 수 있습니다.

## DB 변경

### 추가 DB 마이그레이션이 꼭 필요한가?

기본 구현만 하면 추가 마이그레이션은 거의 필요 없습니다.

이미 메시지 스키마에 아래 컬럼들이 있기 때문입니다.

```sql
direct_messages.edited_at
direct_messages.deleted_at
message_participants.deleted_at
```

### 기존 컬럼 활용 방식

```sql
direct_messages.edited_at
```

메시지를 수정한 시간을 저장합니다.

```sql
direct_messages.deleted_at
```

메시지를 soft delete 처리합니다.

```sql
message_participants.deleted_at
```

대화방을 나간 사용자를 표시합니다.

### 선택 DB 변경

대화방을 나간 뒤 다시 들어왔을 때 과거 메시지를 숨기고 싶다면 아래 컬럼을 추가할 수 있습니다.

```sql
ALTER TABLE message_participants
ADD COLUMN hidden_before TIMESTAMPTZ;
```

하지만 MVP에서는 추천하지 않습니다.

지금은 `deleted_at`만 사용해서 변경 범위를 줄이는 것이 좋습니다.

## 새로 추가될 파일

### UpdateMessageRequest.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/dto/request/UpdateMessageRequest.java
```

역할:

```java
public record UpdateMessageRequest(
        @NotBlank
        @Size(max = 2000)
        String content
) {
}
```

메시지 수정 API의 request body를 받습니다.

## 수정될 기존 파일

### MessageController.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/controller/MessageController.java
```

바뀌는 점:

- 메시지 수정 API 추가
- 메시지 삭제 API 추가
- 대화방 나가기 API 추가

추가될 메서드:

```java
@PatchMapping("/conversations/{conversationId}/messages/{messageId}")
public ApiResponse<MessageResponse> updateMessage(...)
```

```java
@DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
public ApiResponse<Void> deleteMessage(...)
```

```java
@DeleteMapping("/conversations/{conversationId}")
public ApiResponse<Void> leaveConversation(...)
```

### MessageService.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/service/MessageService.java
```

바뀌는 점:

- 메시지 수정 로직 추가
- 메시지 삭제 로직 추가
- 대화방 나가기 로직 추가
- 대화방 재생성 또는 재활성화 정책 보강

추가될 주요 메서드:

```java
public MessageResponse updateMessage(...)
```

```java
public void deleteMessage(...)
```

```java
public void leaveConversation(...)
```

### DirectMessage.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/entity/DirectMessage.java
```

바뀌는 점:

- 메시지 내용 수정 메서드 추가
- 메시지 soft delete 메서드 추가
- 작성자 검증용 메서드 추가

예상 추가 코드:

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

### MessageParticipant.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/entity/MessageParticipant.java
```

바뀌는 점:

- 대화방 나가기 메서드 추가
- 나간 대화방 재활성화 메서드 추가 가능

예상 추가 코드:

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

### MessageResponse.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/dto/response/MessageResponse.java
```

바뀌는 점:

- `editedAt` 필드 추가

Before:

```java
public record MessageResponse(
        UUID id,
        String senderId,
        String text,
        OffsetDateTime createdAt,
        boolean read,
        String deliveryStatus,
        List<MessageAttachmentResponse> attachments,
        SharedPostPreviewResponse sharedPost
) {
}
```

After:

```java
public record MessageResponse(
        UUID id,
        String senderId,
        String text,
        OffsetDateTime createdAt,
        OffsetDateTime editedAt,
        boolean read,
        String deliveryStatus,
        List<MessageAttachmentResponse> attachments,
        SharedPostPreviewResponse sharedPost
) {
}
```

### MessageResponseAssembler.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/service/MessageResponseAssembler.java
```

바뀌는 점:

- `MessageResponse` 생성 시 `message.getEditedAt()` 매핑 추가

Before:

```java
return new MessageResponse(
        message.getId(),
        senderId,
        text,
        message.getCreatedAt(),
        read,
        "sent",
        attachments,
        sharedPost
);
```

After:

```java
return new MessageResponse(
        message.getId(),
        senderId,
        text,
        message.getCreatedAt(),
        message.getEditedAt(),
        read,
        "sent",
        attachments,
        sharedPost
);
```

### DirectMessageRepository.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/repository/DirectMessageRepository.java
```

바뀌는 점:

- 메시지 단건 조회 메서드 추가

예상 추가 코드:

```java
Optional<DirectMessage> findByIdAndConversationIdAndDeletedAtIsNull(UUID id, UUID conversationId);
```

### MessageParticipantRepository.java

경로:

```text
src/main/java/com/gamerin/backend/domain/message/repository/MessageParticipantRepository.java
```

바뀌는 점:

- 삭제된 참여자까지 포함해서 조회하는 메서드 추가 가능

예상 추가 코드:

```java
Optional<MessageParticipant> findByConversationIdAndUserId(UUID conversationId, UUID userId);
```

이 메서드는 나간 대화방을 다시 활성화할 때 필요합니다.

## 기존 로직 영향

### 메시지 목록 조회

기존처럼 `deleted_at is null` 조건을 유지합니다.

삭제된 메시지는 목록에서 사라집니다.

### 대화방 목록 조회

기존처럼 active participant 기준으로 조회합니다.

대화방을 나간 사용자는 목록에서 해당 대화방이 사라집니다.

### 메시지 전송

기존 전송 로직은 유지됩니다.

다만 재대화 정책을 적용하면, 나갔던 대화방에 다시 메시지를 보낼 때 participant를 복구하는 코드가 추가될 수 있습니다.

### 읽음 처리

기존 읽음 처리 로직은 유지됩니다.

나간 사용자는 participant 조회에서 제외되므로 읽음 처리 API 접근이 막힙니다.

### 첨부파일

기존 첨부파일 업로드 로직은 유지됩니다.

메시지 삭제 시 첨부파일 실제 파일까지 삭제할지는 별도 정책입니다.

MVP에서는 DB 메시지만 soft delete하고 파일은 유지하는 쪽이 변경량이 적습니다.

## 프론트에서 바뀌는 점

### 메시지 말풍선

- 내 메시지에 수정 버튼 표시
- 내 메시지에 삭제 버튼 표시
- `editedAt`이 있으면 `수정됨` 표시

### 대화방 목록

- 대화방 메뉴에 `삭제` 또는 `나가기` 버튼 표시
- API 성공 후 해당 대화방을 목록에서 제거

### 대화방 상세

- 메시지 수정 성공 시 기존 메시지를 응답값으로 교체
- 메시지 삭제 성공 시 화면에서 해당 메시지 제거
- 대화방 나가기 성공 시 목록 화면으로 이동

## 권한 정책

### 메시지 수정

작성자 본인만 가능.

상대 메시지를 수정하면 `403 FORBIDDEN`.

### 메시지 삭제

작성자 본인만 가능.

상대 메시지를 삭제하면 `403 FORBIDDEN`.

### 대화방 나가기

대화방 참여자만 가능.

나간 뒤에는 해당 대화방 조회, 메시지 전송, 읽음 처리 불가.

## 구현 순서 추천

1. `UpdateMessageRequest` 추가
2. `MessageResponse`에 `editedAt` 추가
3. `MessageResponseAssembler` 매핑 수정
4. `DirectMessage`에 수정/삭제 메서드 추가
5. `MessageParticipant`에 나가기/재활성화 메서드 추가
6. Repository 단건 조회 메서드 추가
7. `MessageService`에 수정/삭제/나가기 로직 추가
8. `MessageController`에 API 3개 추가
9. 단위 테스트 추가
10. Swagger 또는 HTTP 요청으로 수동 테스트

## 테스트 범위

### 메시지 수정

- 내 메시지 수정 성공
- 상대 메시지 수정 실패
- 없는 메시지 수정 실패
- 삭제된 메시지 수정 실패
- 빈 문자열 수정 실패
- 2000자 초과 수정 실패

### 메시지 삭제

- 내 메시지 삭제 성공
- 상대 메시지 삭제 실패
- 삭제 후 목록에서 제외
- 삭제 후 대화방 preview 갱신 확인

### 대화방 나가기

- 대화방 나가기 성공
- 내 대화방 목록에서 사라짐
- 상대방 대화방 목록에는 유지됨
- 나간 대화방 상세 조회 실패
- 나간 대화방 메시지 전송 실패
- 같은 상대에게 다시 대화 시작 시 기존 대화방 재사용 확인

## 주의할 점

### 대화방 나가기 이름

프론트 UX에서는 `대화방 삭제`라고 보여줘도 됩니다.

하지만 백엔드 의미는 전체 삭제가 아니라 `내 목록에서만 삭제`입니다.

### 메시지 삭제 정책

이번 명세는 작성자 기준 모두에게 삭제입니다.

만약 사용자별로만 메시지를 숨기는 기능이 필요하면 별도 테이블이 필요합니다.

예:

```sql
message_hidden_users (
    message_id UUID,
    user_id UUID,
    hidden_at TIMESTAMPTZ
)
```

현재 단계에서는 추천하지 않습니다.

### 실시간 반영

현재 메시지 기능은 REST 기반입니다.

따라서 상대방 화면에 수정/삭제/나가기 상태가 즉시 반영되지는 않습니다.

프론트는 polling 또는 재조회로 최신 상태를 반영해야 합니다.

## 최종 결과

이 개발이 완료되면 DM 기능은 단순 전송/조회에서 아래 수준으로 확장됩니다.

- 메시지 작성 후 수정 가능
- 메시지 작성 후 삭제 가능
- 사용자가 대화방 목록을 정리 가능
- 서버에는 전체 대화 데이터가 유지됨
- 프론트는 인스타그램 DM에 가까운 기본 관리 UX를 제공 가능

