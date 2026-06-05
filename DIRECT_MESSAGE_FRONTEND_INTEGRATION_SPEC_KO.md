# 다이렉트 메시지 프론트엔드 연동 명세서

## 목적

인스타그램 DM 형태의 1:1 메시지 기능을 프론트엔드에서 연동하기 위한 명세서입니다.

현재 백엔드는 REST API 기반으로 동작합니다. 실시간 WebSocket/SSE 푸시는 아직 없으므로, 프론트에서는 메시지 전송 후 재조회하거나 일정 주기로 polling 하는 방식으로 최신 상태를 반영합니다.

## 공통 규칙

- Base URL: `http://localhost:8080`
- API Prefix: `/api/v1/messages`
- 인증: 모든 메시지 API는 `Authorization: Bearer {accessToken}` 필요
- 공통 응답 형식:

```json
{
  "success": true,
  "data": {}
}
```

- 인증 실패 응답 예시:

```json
{
  "success": false,
  "message": "Authentication is required or the token has expired.",
  "data": null
}
```

## 데이터 모델

### MessageRecipientResponse

```ts
type MessageRecipientResponse = {
  id: string;
  name: string;
  handle: string;
  role: string;
  online: boolean;
};
```

### MessageAttachmentResponse

```ts
type MessageAttachmentResponse = {
  id: string;
  type: "image" | "video";
  name: string;
  url: string;
};
```

### SharedPostPreviewResponse

```ts
type SharedPostPreviewResponse = {
  postId: string;
  author: string;
  authorHandle: string;
  content: string;
  createdAt: string;
};
```

### MessageResponse

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

`senderId`가 `"me"`이면 현재 로그인한 사용자가 보낸 메시지입니다. 그 외에는 상대방 사용자 UUID입니다.

### ConversationResponse

```ts
type ConversationResponse = {
  id: string;
  recipient: MessageRecipientResponse;
  messages: MessageResponse[];
  unreadCount: number;
  updatedAt: string;
};
```

`messages`에는 대화방 목록 화면에서 바로 보여줄 수 있는 최근 메시지들이 포함됩니다.

## API 목록

### 1. 메시지 수신자 검색

사용자가 메시지를 보낼 상대를 검색할 때 사용합니다.

```http
GET /api/v1/messages/recipients?keyword={keyword}&size={size}
Authorization: Bearer {accessToken}
```

Query:

```ts
type Query = {
  keyword?: string;
  size?: number;
};
```

응답:

```json
{
  "success": true,
  "data": [
    {
      "id": "user-uuid",
      "name": "DM Tester B",
      "handle": "@dmtestb",
      "role": "USER",
      "online": false
    }
  ]
}
```

프론트 사용 위치:

- DM 새 메시지 작성 모달
- 사용자 검색 입력창
- 게시글 공유 대상 검색

### 2. 대화방 생성 또는 기존 대화방 조회

상대 사용자와 1:1 대화방을 생성합니다. 이미 대화방이 있으면 기존 대화방을 반환합니다.

```http
POST /api/v1/messages/conversations
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Body는 `recipientHandle` 또는 `recipientId` 중 하나를 사용합니다.

```json
{
  "recipientHandle": "dmtestb"
}
```

또는:

```json
{
  "recipientId": "user-uuid"
}
```

응답:

```json
{
  "success": true,
  "data": {
    "id": "conversation-uuid",
    "recipient": {
      "id": "user-uuid",
      "name": "DM Tester B",
      "handle": "@dmtestb",
      "role": "USER",
      "online": false
    },
    "messages": [],
    "unreadCount": 0,
    "updatedAt": "2026-05-25T10:00:00+09:00"
  }
}
```

주의:

- 자기 자신에게 대화방 생성 불가
- 삭제/비활성 유저에게 전송 불가

### 3. 대화방 목록 조회

왼쪽 DM 사이드바 또는 메시지 목록 화면에서 사용합니다.

```http
GET /api/v1/messages/conversations
Authorization: Bearer {accessToken}
```

응답:

```json
{
  "success": true,
  "data": [
    {
      "id": "conversation-uuid",
      "recipient": {
        "id": "user-uuid",
        "name": "DM Tester B",
        "handle": "@dmtestb",
        "role": "USER",
        "online": false
      },
      "messages": [
        {
          "id": "message-uuid",
          "senderId": "me",
          "text": "안녕하세요",
          "createdAt": "2026-05-25T10:01:00+09:00",
          "read": false,
          "deliveryStatus": "sent",
          "attachments": [],
          "sharedPost": null
        }
      ],
      "unreadCount": 0,
      "updatedAt": "2026-05-25T10:01:00+09:00"
    }
  ]
}
```

프론트 처리:

- `recipient.name`, `recipient.handle`을 대화 상대 정보로 표시
- `messages`의 마지막 요소를 최근 메시지 preview로 사용
- `unreadCount > 0`이면 unread badge 표시
- `updatedAt` 기준으로 최신 대화방 순서 표시

### 4. 메시지 목록 조회

특정 대화방의 메시지들을 커서 페이지 방식으로 조회합니다.

```http
GET /api/v1/messages/conversations/{conversationId}/messages?cursor={cursor}&size={size}
Authorization: Bearer {accessToken}
```

Query:

```ts
type Query = {
  cursor?: string;
  size?: number; // 기본 30, 최대 50
};
```

응답:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "message-uuid",
        "senderId": "me",
        "text": "안녕하세요",
        "createdAt": "2026-05-25T10:01:00+09:00",
        "read": false,
        "deliveryStatus": "sent",
        "attachments": [],
        "sharedPost": null
      }
    ],
    "nextCursor": "2026-05-25T10:01:00+09:00|message-uuid",
    "hasNext": true
  }
}
```

프론트 처리:

- 최초 진입 시 `cursor` 없이 호출
- 과거 메시지 더보기 시 `nextCursor`로 다음 호출
- `hasNext === false`이면 더 이상 불러올 메시지 없음
- 응답 `items`는 시간 오름차순 표시용으로 내려옵니다.

### 5. 텍스트 메시지 전송

텍스트만 보내거나, 텍스트와 게시글 공유를 같이 보낼 때 사용합니다.

```http
POST /api/v1/messages/conversations/{conversationId}/messages
Authorization: Bearer {accessToken}
Content-Type: application/json
```

텍스트 메시지:

```json
{
  "content": "안녕하세요",
  "sharedPostId": null
}
```

게시글 공유 메시지:

```json
{
  "content": "이 게시글 봐봐",
  "sharedPostId": "post-uuid"
}
```

응답:

```json
{
  "success": true,
  "data": {
    "id": "message-uuid",
    "senderId": "me",
    "text": "안녕하세요",
    "createdAt": "2026-05-25T10:01:00+09:00",
    "read": false,
    "deliveryStatus": "sent",
    "attachments": [],
    "sharedPost": null
  }
}
```

제약:

- `content` 최대 2000자
- `content`와 `sharedPostId`가 모두 비어 있으면 실패

### 6. 첨부 메시지 전송

이미지 또는 영상 파일을 DM으로 보낼 때 사용합니다.

```http
POST /api/v1/messages/conversations/{conversationId}/messages
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
```

FormData:

```ts
const formData = new FormData();
formData.append("content", "이미지 보냅니다");
formData.append("attachments", file);
```

여러 이미지:

```ts
files.forEach((file) => {
  formData.append("attachments", file);
});
```

응답:

```json
{
  "success": true,
  "data": {
    "id": "message-uuid",
    "senderId": "me",
    "text": "이미지 보냅니다",
    "createdAt": "2026-05-25T10:02:00+09:00",
    "read": false,
    "deliveryStatus": "sent",
    "attachments": [
      {
        "id": "attachment-uuid",
        "type": "image",
        "name": "sample.png",
        "url": "http://localhost:8080/uploads/message-attachments/file.png"
      }
    ],
    "sharedPost": null
  }
}
```

첨부 제약:

- 이미지 최대 4개
- 영상 최대 1개
- 이미지와 영상을 한 메시지에 섞어서 전송 불가
- 이미지 파일 최대 20MB
- 영상 파일 최대 100MB
- 지원 타입:
  - 이미지: `image/*`, 또는 `.jpg`, `.jpeg`, `.png`, `.gif`, `.webp`
  - 영상: `video/*`, 또는 `.mp4`, `.mov`, `.webm`, `.m4v`

프론트 표시:

- `attachment.type === "image"`이면 `<img src={url} />`
- `attachment.type === "video"`이면 `<video src={url} controls />`

### 7. 읽음 처리

사용자가 대화방에 진입했거나 메시지 목록을 확인했을 때 호출합니다.

```http
PATCH /api/v1/messages/conversations/{conversationId}/read
Authorization: Bearer {accessToken}
```

응답:

```json
{
  "success": true,
  "data": null
}
```

프론트 처리:

- 대화방 상세 진입 시 호출
- 성공 후 해당 대화방의 `unreadCount`를 0으로 갱신

### 8. 게시글 공유

피드 게시글을 DM으로 여러 사용자에게 공유할 때 사용합니다.

```http
POST /api/v1/messages/share-post
Authorization: Bearer {accessToken}
Content-Type: application/json
```

Body:

```json
{
  "postId": "post-uuid",
  "recipientHandles": ["dmtestb", "dmtestc"],
  "content": "이 게시글 봐봐"
}
```

또는:

```json
{
  "postId": "post-uuid",
  "recipientIds": ["user-uuid-1", "user-uuid-2"],
  "content": "이 게시글 봐봐"
}
```

응답:

```json
{
  "success": true,
  "data": [
    {
      "id": "conversation-uuid",
      "recipient": {
        "id": "user-uuid",
        "name": "DM Tester B",
        "handle": "@dmtestb",
        "role": "USER",
        "online": false
      },
      "messages": [
        {
          "id": "message-uuid",
          "senderId": "me",
          "text": "이 게시글 봐봐",
          "createdAt": "2026-05-25T10:03:00+09:00",
          "read": false,
          "deliveryStatus": "sent",
          "attachments": [],
          "sharedPost": {
            "postId": "post-uuid",
            "author": "작성자닉네임",
            "authorHandle": "authorHandle",
            "content": "게시글 내용",
            "createdAt": "2026-05-25T09:50:00+09:00"
          }
        }
      ],
      "unreadCount": 0,
      "updatedAt": "2026-05-25T10:03:00+09:00"
    }
  ]
}
```

주의:

- `recipientHandles` 또는 `recipientIds` 중 하나 이상 필요
- 중복 대상은 서버에서 한 명으로 정리됨
- 공유 대상마다 1:1 대화방이 생성되거나 기존 대화방에 메시지가 추가됨

## 프론트 구현 권장 흐름

### DM 목록 화면

1. `GET /api/v1/messages/conversations`
2. 대화방 목록 렌더링
3. 각 대화방의 마지막 메시지를 preview로 표시
4. `unreadCount` badge 표시

### DM 상세 화면

1. URL 또는 상태에서 `conversationId` 확보
2. `GET /api/v1/messages/conversations/{conversationId}/messages`
3. 메시지 렌더링
4. 진입 직후 `PATCH /api/v1/messages/conversations/{conversationId}/read`
5. 메시지 전송 성공 시 응답 메시지를 즉시 화면에 append
6. 필요하면 5~10초 단위 polling으로 새 메시지 재조회

### 새 메시지 작성

1. 검색창 입력
2. `GET /api/v1/messages/recipients?keyword=...`
3. 상대 선택
4. `POST /api/v1/messages/conversations`
5. 반환된 `conversation.id`로 상세 화면 이동

### 게시글 공유

1. 게시글 공유 버튼 클릭
2. 수신자 검색 또는 선택
3. `POST /api/v1/messages/share-post`
4. 성공 시 공유 완료 토스트 표시

## 프론트 Codex 작업 지시 요약

- 기존 mock 메시지 데이터를 API 응답 기반으로 교체합니다.
- `MessageResponse.senderId === "me"`를 기준으로 내 메시지/상대 메시지 스타일을 분기합니다.
- 대화방 목록의 preview는 `conversation.messages.at(-1)`를 사용합니다.
- 첨부 파일은 `attachments` 배열 기준으로 이미지/영상 렌더링을 분기합니다.
- 게시글 공유 카드는 `sharedPost !== null`일 때만 렌더링합니다.
- 현재 백엔드는 실시간 푸시가 없으므로 전송 직후 optimistic append 또는 재조회 방식으로 처리합니다.
- 인증 만료 응답이 오면 기존 auth refresh/login 흐름으로 넘깁니다.

## 자체 테스트 완료 항목

백엔드에서 실제 HTTP 요청으로 아래 흐름을 확인했습니다.

- 사용자 A/B 회원가입
- A가 B 검색
- A가 B와 대화방 생성
- A가 B에게 텍스트 메시지 전송
- A가 메시지 목록 조회
- B의 대화방 목록에서 `unreadCount = 1` 확인
- B가 읽음 처리 후 `unreadCount = 0` 확인

