# 프론트 피드 연동 명세서

## 1. 개요
- 서버 주소: `http://localhost:8080`
- Swagger 주소: `http://localhost:8080/swagger-ui/index.html`
- 모든 피드/게시글 API는 로그인 후 `Authorization: Bearer {accessToken}` 헤더가 필요합니다.
- 업로드된 파일은 `/uploads/**` 경로로 공개 접근됩니다.

## 2. 공통 응답

성공:
```json
{
  "success": true,
  "data": {}
}
```

실패:
```json
{
  "success": false,
  "message": "에러 메시지"
}
```

커서 페이지:
```json
{
  "success": true,
  "data": {
    "items": [],
    "nextCursor": "cursor-or-null",
    "hasNext": true
  }
}
```

## 3. 게시글 작성

### 3-1. JSON 작성
`POST /api/v1/posts`

텍스트-only 게시글을 작성할 때 사용합니다.

```json
{
  "content": "오늘 경기 하이라이트"
}
```

주의:
- `gameName`은 더 이상 게시글 작성 입력값으로 받지 않습니다.
- 외부 링크 카드 작성은 지원하지 않습니다.

### 3-2. 파일 업로드 작성
`POST /api/v1/posts`

Content-Type: `multipart/form-data`

필드:
- `content`: 선택
- `mediaFiles`: 이미지 또는 영상 파일
- `thumbnailFile`: 영상 업로드 시 선택

규칙:
- `content`, `mediaFiles` 중 하나는 필수입니다.
- 이미지 최대 4장까지 업로드할 수 있습니다.
- 영상은 최대 1개만 업로드할 수 있습니다.
- 영상 파일은 최대 500MB까지 업로드할 수 있습니다.
- 영상 길이는 최대 2분까지 허용됩니다.
- 이미지와 영상은 한 게시글에 함께 업로드할 수 없습니다.
- 영상 업로드 시 `thumbnailFile`은 선택입니다. 보내면 이미지 파일이어야 합니다.
- 이미지 업로드 시 `thumbnailFile`은 사용할 수 없습니다.
- `gameName`은 더 이상 사용하지 않습니다.
- 외부 링크 카드 작성은 지원하지 않습니다.

## 4. 게시글 응답

`PostCardResponse`와 `PostDetailResponse`는 동일한 핵심 필드를 사용합니다.

```json
{
  "postId": "uuid",
  "author": "Tester",
  "authorHandle": "tester",
  "authorProfileImageUrl": null,
  "authorVerifiedBadge": false,
  "content": "오늘 경기 하이라이트",
  "media": [],
  "likes": 3,
  "comments": 1,
  "shares": 2,
  "likedByMe": false,
  "bookmarkedByMe": true,
  "mine": false,
  "createdAt": "2026-05-06T21:00:00+09:00"
}
```

미디어 예시:
```json
{
  "mediaId": "uuid",
  "mediaType": "IMAGE",
  "mediaUrl": "http://localhost:8080/uploads/post-media/file.jpg",
  "thumbnailUrl": null,
  "sortOrder": 0
}
```

## 5. 피드 API

### 전체/팔로잉 피드
`GET /api/v1/feed?tab=all|following&cursor=&size=`

- `tab=all`: 전체 피드
- `tab=following`: 내 글 + 내가 팔로우한 사용자 글
- 기본 `size=20`, 최대 `size=50`
- 커서 형식: `createdAt|postId`

## 6. 게시글 액션 API

### 좋아요
`POST /api/v1/posts/{postId}/likes`

- 이미 좋아요한 상태면 에러 없이 처리됩니다.

### 좋아요 취소
`DELETE /api/v1/posts/{postId}/likes`

- 좋아요 상태가 아니어도 에러 없이 처리됩니다.

### 댓글 작성
`POST /api/v1/posts/{postId}/comments`

```json
{
  "content": "좋은 글이네요"
}
```

### 북마크
`POST /api/v1/posts/{postId}/bookmarks`

- 게시글을 내 저장 목록에 추가합니다.
- 이미 북마크한 상태면 에러 없이 처리됩니다.
- 성공 후 프론트는 해당 게시글의 `bookmarkedByMe`를 `true`로 갱신하면 됩니다.

### 북마크 취소
`DELETE /api/v1/posts/{postId}/bookmarks`

- 저장 목록에서 게시글을 제거합니다.
- 북마크 상태가 아니어도 에러 없이 처리됩니다.
- 성공 후 프론트는 해당 게시글의 `bookmarkedByMe`를 `false`로 갱신하면 됩니다.

### 게시글 링크 공유 기록
`POST /api/v1/posts/{postId}/shares`

요청:
```json
{
  "target": "COPY_LINK"
}
```

`target` 값:
- `COPY_LINK`
- `WEB_SHARE`
- `KAKAO`
- `X`
- `FACEBOOK`
- `OTHER`

응답:
```json
{
  "success": true,
  "data": {
    "postId": "uuid",
    "shares": 4
  }
}
```

주의:
- 이 API는 실제 공유 UI를 실행하지 않습니다.
- 프론트가 링크 복사, Web Share API, 카카오 공유 등을 처리한 뒤 서버에 공유 기록을 남기는 용도입니다.
- 공유는 이벤트 기록이므로 같은 사용자가 같은 게시글을 여러 번 공유할 수 있습니다.

## 7. 프로필 API

### 내 프로필
`GET /api/v1/users/me`

### 특정 사용자 프로필
`GET /api/v1/users/{handle}`

### 프로필 posts 탭
`GET /api/v1/users/{handle}/posts?cursor=&size=`

- 기본 `size=20`, 최대 `size=50`
- 커서 형식: `createdAt|postId`

### 프로필 media 탭
`GET /api/v1/users/{handle}/media?cursor=&size=`

- 기본 `size=24`, 최대 `size=50`
- 커서 형식: `createdAt|postId|sortOrder|mediaId`
- 직접 업로드한 이미지/영상만 포함합니다.

### 내 북마크 목록
`GET /api/v1/users/me/bookmarks?cursor=&size=`

- 내가 저장한 게시글을 최신 저장순으로 조회합니다.
- 기본 `size=20`, 최대 `size=50`
- 커서 형식: `bookmarkCreatedAt|bookmarkId`
- 응답은 `CursorPageResponse<PostCardResponse>`입니다.

## 8. 팔로우 API

### 팔로우
`POST /api/v1/users/{handle}/follow`

- 자기 자신은 팔로우할 수 없습니다.
- 이미 팔로우 중이면 에러 없이 처리됩니다.

### 언팔로우
`DELETE /api/v1/users/{handle}/follow`

- 팔로우 상태가 아니어도 에러 없이 처리됩니다.

## 9. 프론트 렌더링 규칙

- `media.length > 0`이면 업로드 미디어 UI를 보여줍니다.
- `likedByMe`로 좋아요 버튼 상태를 표시합니다.
- `bookmarkedByMe`로 북마크 버튼 상태를 표시합니다.
- `shares`는 공유 API 응답의 값으로 갱신합니다.

예시:
```tsx
if (post.media.length > 0) {
  return <UploadedMediaBlock media={post.media} />;
}
```

## 10. 검증된 항목

- 이미지 업로드 게시글 생성
- 썸네일 없는 영상 업로드 게시글 생성
- 전체 피드 조회
- 프로필 posts/media 조회
- 좋아요/좋아요 취소
- 댓글 작성
- 북마크/북마크 취소
- 내 북마크 목록 조회
- 게시글 공유 기록 및 `shares` 카운트 증가
