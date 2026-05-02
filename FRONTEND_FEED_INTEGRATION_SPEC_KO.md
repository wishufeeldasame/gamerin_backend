# 프론트 피드 연동 명세서

## 1. 개요
- 서버 주소: `http://localhost:8080`
- Swagger 주소: `http://localhost:8080/swagger-ui/index.html`
- 지원 기능:
  - 홈 피드 조회
  - 팔로잉 피드 조회
  - 게시글 작성
  - 게시글 상세 조회
  - 좋아요 / 좋아요 취소
  - 댓글 작성
  - 프로필 조회
  - 프로필 `posts` 탭 조회
  - 프로필 `media` 탭 조회
  - 팔로우 / 언팔로우
  - 직접 업로드 이미지 / 영상 게시글
  - 외부 링크 카드 게시글

## 2. 인증 방식
- 로그인 또는 회원가입 성공 시 응답 본문에 `accessToken`이 내려옵니다.
- 동시에 `refresh_token` 쿠키가 `Set-Cookie`로 내려옵니다.
- 인증이 필요한 API는 아래 헤더를 사용합니다.

```http
Authorization: Bearer {accessToken}
```

- 브라우저에서 refresh까지 사용할 경우 `credentials: "include"` 또는 `withCredentials: true` 설정이 필요합니다.

## 3. 공통 응답 형식

### 성공
```json
{
  "success": true,
  "data": {}
}
```

### 실패
```json
{
  "success": false,
  "message": "에러 메시지"
}
```

### 커서 페이지 응답
```json
{
  "success": true,
  "data": {
    "items": [],
    "nextCursor": "opaque-cursor-or-null",
    "hasNext": true
  }
}
```

- `nextCursor`는 프론트에서 해석하지 않고 그대로 다음 요청에 넘기면 됩니다.

## 4. 인증 API

### 4-1. 회원가입
`POST /api/v1/auth/signup`

요청 예시:
```json
{
  "handle": "feeda205114",
  "nickname": "feeda205114",
  "email": "feeda205114@test.com",
  "password": "Test1234!",
  "passwordConfirm": "Test1234!",
  "agreedToTerms": true,
  "agreedToPrivacy": true
}
```

### 4-2. 로그인
`POST /api/v1/auth/login`

요청 예시:
```json
{
  "handle": "feeda205114",
  "password": "Test1234!"
}
```

## 5. 게시글 작성 방식

### 5-1. 직접 업로드 미디어 게시글
- 엔드포인트: `POST /api/v1/posts`
- Content-Type: `multipart/form-data`

폼 필드:
- `content`: 선택
- `gameName`: 선택
- `mediaFiles`: 파일 배열
- `thumbnailFile`: 영상 업로드 시만 사용
- `durationSeconds`: 영상 업로드 시만 사용

규칙:
- `content` 또는 `mediaFiles` 중 하나는 필수
- 이미지 최대 4장
- 영상 최대 1개
- 이미지와 영상 혼합 업로드 불가
- 영상 업로드 시 `thumbnailFile` 필수
- 영상 업로드 시 `durationSeconds` 필수
- 이미지 업로드 시 `thumbnailFile`, `durationSeconds` 사용 불가

예시:
- 이미지 게시글
  - `content`: `오늘 경기 하이라이트`
  - `gameName`: `PUBG`
  - `mediaFiles`: `image1.jpg`
  - `mediaFiles`: `image2.jpg`
- 영상 게시글
  - `content`: `킬캠 영상`
  - `gameName`: `PUBG`
  - `mediaFiles`: `video.mp4`
  - `thumbnailFile`: `video-thumb.jpg`
  - `durationSeconds`: `12`

### 5-2. 외부 링크 카드 게시글
- 외부 링크는 업로드 미디어처럼 재생하지 않고 카드로만 노출합니다.
- YouTube, 기사 링크, 블로그 링크, 기타 일반 URL 모두 동일하게 카드 처리합니다.

#### JSON 요청
`POST /api/v1/posts`

```json
{
  "content": "이 링크 참고",
  "gameName": "PUBG",
  "media": [],
  "externalLink": {
    "url": "https://example.com/article"
  }
}
```

#### multipart 요청
`POST /api/v1/posts`

폼 필드:
- `content`
- `gameName`
- `externalLinkUrl`

예시:
- `content`: `이 링크 참고`
- `externalLinkUrl`: `https://example.com/article`

### 5-3. 미디어와 외부 링크 동시 사용 규칙
- 업로드 미디어와 외부 링크는 같은 게시글에 같이 넣을 수 없습니다.
- 둘 중 하나만 선택해야 합니다.
  - 업로드 미디어 게시글
  - 외부 링크 카드 게시글

## 6. 응답 DTO

### PostMediaResponse
```json
{
  "mediaId": "uuid",
  "mediaType": "IMAGE",
  "mediaUrl": "http://localhost:8080/uploads/post-media/file.jpg",
  "thumbnailUrl": null,
  "sortOrder": 0,
  "durationSeconds": null
}
```

### ExternalLinkCardResponse
```json
{
  "url": "https://example.com/article",
  "host": "example.com",
  "title": "example.com",
  "description": "https://example.com/article",
  "thumbnailUrl": null
}
```

### PostCardResponse / PostDetailResponse
```json
{
  "postId": "uuid",
  "author": "feeda205114",
  "authorHandle": "feeda205114",
  "authorProfileImageUrl": null,
  "authorVerifiedBadge": false,
  "game": "PUBG",
  "content": "오늘 경기 하이라이트",
  "media": [],
  "externalLink": null,
  "likes": 0,
  "comments": 0,
  "shares": 0,
  "likedByMe": false,
  "mine": true,
  "createdAt": "2026-05-02T21:19:38.885745+09:00"
}
```

### 외부 링크 카드 게시글 응답 예시
```json
{
  "postId": "uuid",
  "content": "이 링크 참고",
  "media": [],
  "externalLink": {
    "url": "https://youtu.be/abc123",
    "host": "youtu.be",
    "title": "YouTube",
    "description": "https://youtu.be/abc123",
    "thumbnailUrl": "https://img.youtube.com/vi/abc123/hqdefault.jpg"
  }
}
```

### ProfileMediaItemResponse
```json
{
  "mediaId": "uuid",
  "postId": "uuid",
  "authorHandle": "feedb205114",
  "mediaType": "VIDEO",
  "mediaUrl": "http://localhost:8080/uploads/post-media/video.mp4",
  "thumbnailUrl": "http://localhost:8080/uploads/post-media/video-thumb.jpg",
  "createdAt": "2026-05-02T20:51:20.123+09:00"
}
```

### UserProfileResponse
```json
{
  "id": "uuid",
  "handle": "feeda205114",
  "nickname": "feeda205114",
  "bio": null,
  "profileImageUrl": null,
  "gameStats": {},
  "verifiedBadge": false,
  "followersCount": 0,
  "followingCount": 1,
  "postCount": 2,
  "mediaPostCount": 1,
  "mediaItemCount": 1
}
```

## 7. 피드 / 게시글 / 프로필 API

### 7-1. 전체 피드 / 팔로잉 피드
`GET /api/v1/feed?tab=all|following&cursor=&size=`

- `tab=all`: 전체 피드
- `tab=following`: 나 + 내가 팔로우한 유저 글
- 기본 `size=20`
- 최대 `size=50`
- 커서 형식: `createdAt|postId`

### 7-2. 트렌딩 게임
`GET /api/v1/feed/trending/games`

- 최근 7일 기준 집계
- 최대 10개 게임 반환

### 7-3. 게시글 작성
`POST /api/v1/posts`

- JSON 방식: URL 기반 미디어 또는 외부 링크 카드
- multipart 방식: 직접 파일 업로드 또는 외부 링크 카드

### 7-4. 게시글 상세
`GET /api/v1/posts/{postId}`

### 7-5. 좋아요
`POST /api/v1/posts/{postId}/likes`

- 이미 좋아요 상태여도 에러 없이 유지됩니다

### 7-6. 좋아요 취소
`DELETE /api/v1/posts/{postId}/likes`

- 좋아요가 없어도 에러 없이 처리됩니다

### 7-7. 댓글 작성
`POST /api/v1/posts/{postId}/comments`

요청 예시:
```json
{
  "content": "좋은 글이네요"
}
```

### 7-8. 내 프로필
`GET /api/v1/users/me`

### 7-9. 특정 유저 프로필
`GET /api/v1/users/{handle}`

### 7-10. 프로필 posts 탭
`GET /api/v1/users/{handle}/posts?cursor=&size=`

- 기본 `size=20`
- 최대 `size=50`
- 커서 형식: `createdAt|postId`

### 7-11. 프로필 media 탭
`GET /api/v1/users/{handle}/media?cursor=&size=`

- 기본 `size=24`
- 최대 `size=50`
- 커서 형식: `createdAt|postId|sortOrder|mediaId`
- 텍스트-only 게시글 제외
- 직접 업로드한 사진 / 영상만 포함
- 외부 링크 카드는 포함하지 않음

### 7-12. 팔로우
`POST /api/v1/users/{handle}/follow`

- 자기 자신은 팔로우 불가
- 이미 팔로우 중이면 에러 없이 유지

### 7-13. 언팔로우
`DELETE /api/v1/users/{handle}/follow`

- 팔로우 상태가 아니어도 에러 없이 처리

## 8. 프론트 렌더링 규칙
- `media.length > 0` 이면 업로드 미디어 UI를 렌더링합니다.
- `externalLink != null` 이면 링크 카드 UI를 렌더링합니다.
- 두 값은 동시에 내려오지 않는다고 가정해도 됩니다.

예시:
```tsx
if (post.media.length > 0) {
  return <UploadedMediaBlock media={post.media} />;
}

if (post.externalLink) {
  return <ExternalLinkCard card={post.externalLink} />;
}
```

## 9. 프론트 구현 시 주의사항
- 업로드 미디어와 외부 링크는 함께 사용할 수 없습니다.
- 외부 링크는 모두 카드 전용입니다.
- 외부 링크는 자동 재생 / 임베드가 아닙니다.
- YouTube도 현재는 카드로만 보여주고, 클릭 시 외부 이동 처리하면 됩니다.
- 프로필 `media` 탭은 업로드 파일만 포함합니다.
- 업로드된 파일은 인증 없이 `GET /uploads/**`로 접근 가능합니다.
- 영상 업로드 시에는 반드시 썸네일 파일도 함께 보내야 합니다.

## 10. 실제 검증 포인트
- 이미지 업로드 게시글 생성 성공
- 영상 업로드 게시글 생성 성공
- JSON 외부 링크 카드 게시글 생성 성공
- multipart 외부 링크 카드 게시글 생성 성공
- 미디어 + 외부 링크 동시 요청 시 `400`
- 피드 응답에서 `media` 또는 `externalLink`가 올바르게 내려오는지 확인
