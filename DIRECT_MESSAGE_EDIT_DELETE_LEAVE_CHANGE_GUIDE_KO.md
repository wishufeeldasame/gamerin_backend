# 다이렉트 메시지 삭제/대화방 나가기 개발 변경 가이드

## 목적

`DIRECT_MESSAGE_EDIT_DELETE_LEAVE_SPEC_KO.md` 명세대로 개발했을 때 백엔드 코드, DB, API 응답, 프론트 연동 흐름에서 바뀌는 부분을 정리한 가이드입니다.

대상 기능은 아래 2가지입니다.

- 메시지 삭제
- 대화방 나가기 또는 개인 사용자 기준 대화방 삭제

메시지 수정 기능은 현재 지원하지 않습니다.

## API 변경

### 메시지 삭제

```http
DELETE /api/v1/messages/conversations/{conversationId}/messages/{messageId}
```

내가 보낸 메시지를 soft delete 처리합니다.

### 대화방 나가기

```http
DELETE /api/v1/messages/conversations/{conversationId}
```

내 기준으로 대화방을 나갑니다. 서버 전체 삭제가 아니라 해당 사용자에게만 숨김 처리합니다.

## DB 사용 방식

기존 DM 스키마의 아래 컬럼을 사용합니다.

```sql
direct_messages.deleted_at
message_participants.deleted_at
message_participants.cleared_at
```

- `direct_messages.deleted_at`: 메시지 soft delete 시간
- `message_participants.deleted_at`: 대화방을 나간 사용자 표시
- `message_participants.cleared_at`: 대화방 나가기 이전 메시지를 숨기는 기준 시간

공유 게시글만 담은 메시지는 `shared_post_id`가 나중에 `ON DELETE SET NULL` 되어도 체크 제약을 깨지 않도록 `content = ''`로 저장합니다.

## 수정될 기존 파일

### MessageController.java

- 메시지 삭제 API 유지
- 대화방 나가기 API 유지
- 메시지 수정 API는 제공하지 않음

### MessageService.java

- 메시지 삭제 로직 유지
- 대화방 나가기 로직 유지
- 대화방 재진입/재활성화 정책 보강
- 메시지 커서는 `(createdAt, id)` 기준으로 페이징
- 공유 게시글 전용 메시지는 빈 문자열 content로 저장

### DirectMessageRepository.java

- 메시지 커서 조회 조건을 `createdAt` 단독 비교가 아니라 `(createdAt, id)` 튜플 기준으로 처리

### MessageParticipant.java

- `softDelete()`는 `deletedAt`, `clearedAt`을 함께 설정
- 본인 재진입은 `deletedAt`을 해제하고 `lastReadAt`을 갱신
- incoming message 재활성화는 `deletedAt`만 해제하고 `clearedAt`은 유지

## 기존 로직 영향

### 메시지 목록 조회

`deleted_at is null` 조건을 유지합니다.

사용자가 대화방을 나갔다가 다시 들어온 경우에는 `cleared_at` 이후 메시지만 조회합니다.

### 대화방 목록 조회

active participant 기준으로 조회합니다.

대화방을 나간 사용자는 목록에서 해당 대화방이 사라집니다.

### 메시지 전송

전송자가 active participant인지 확인합니다.

상대방이 대화방을 나간 상태라면 실제 새 메시지가 저장될 때 상대방 participant를 incoming 기준으로 재활성화합니다.

### 읽음 처리

나간 사용자는 participant 조회에서 제외되므로 읽음 처리 API 접근이 막힙니다.

## 프론트에서 바뀌는 점

- 내 메시지에 삭제 버튼 표시
- 수정 버튼과 수정 입력 UI는 표시하지 않음
- 메시지 삭제 성공 시 화면에서 해당 메시지 제거
- 대화방 나가기 성공 시 목록 화면으로 이동

## 테스트 범위

### 메시지 삭제

- 내 메시지 삭제 성공
- 상대 메시지 삭제 실패
- 삭제 후 목록에서 제외

### 대화방 나가기

- 대화방 나가기 성공
- 내 대화방 목록에서 사라짐
- 상대방 대화방 목록에는 유지됨
- 나간 대화방 상세 조회 실패
- 나간 대화방 메시지 전송 실패
- 같은 상대에게 다시 대화 시작 시 본인 participant만 재활성화
- 상대방이 새 메시지를 보낼 때 incoming 기준으로 재활성화

### 커서 페이징

- 같은 `createdAt`을 가진 메시지가 페이지 경계에 있어도 누락되지 않음
- 잘못된 cursor 형식은 400으로 응답

### 공유 게시글 메시지

- `content` 없이 `sharedPostId`만 전송해도 빈 문자열 content로 저장
- 기존 `content is null and shared_post_id is not null` 데이터는 마이그레이션으로 보정
