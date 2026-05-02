# 프론트 연동 명세서

## 1. 개요
- 기준 서버: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- 대상 기능:
  - 홈 피드
  - 게시글 작성 / 상세 / 좋아요 / 댓글
  - 프로필 조회
  - 프로필 `posts` 탭
  - 프로필 `media` 탭
  - 팔로우 / 언팔로우

## 2. 인증 방식
- 로그인/회원가입 성공 시 응답 본문에 `accessToken`이 내려옵니다.
- 동시에 `refresh_token` 쿠키가 `Set-Cookie`로 내려옵니다.
- 피드/게시글/프로필/팔로우 API 호출 시 프론트는 아래 헤더를 붙여야 합니다.

```http
Authorization: Bearer {accessToken}
```

- 브라우저 환경에서 `refresh_token` 쿠키를 유지하려면 로그인/회원가입/refresh 요청에 `credentials: "include"` 또는 axios의 `withCredentials: true` 설정을 권장합니다.

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

- `nextCursor`는 프론트에서 해석하지 말고 그대로 다음 요청에 넘기면 됩니다.

## 4. 인증 연동

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

응답 예시:
```json
{
  "success": true,
  "data": {
    "userId": "uuid",
    "handle": "feeda205114",
    "nickname": "feeda205114",
    "accessToken": "jwt-token",
    "accessTokenExpiresIn": 1800
  }
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

## 5. DTO 요약

### PostMediaResponse
```json
{
  "mediaId": "uuid",
  "mediaType": "IMAGE",
  "mediaUrl": "https://example.com/a.jpg",
  "thumbnailUrl": null,
  "sortOrder": 0,
  "durationSeconds": null
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
  "content": "A image post",
  "media": [],
  "likes": 0,
  "comments": 0,
  "shares": 0,
  "likedByMe": false,
  "mine": true,
  "createdAt": "2026-05-02T20:51:14.123+09:00"
}
```

### CommentResponse
```json
{
  "commentId": "uuid",
  "author": "feeda205114",
  "authorHandle": "feeda205114",
  "authorProfileImageUrl": null,
  "authorVerifiedBadge": false,
  "content": "nice post",
  "createdAt": "2026-05-02T20:52:00.123+09:00"
}
```

### ProfileMediaItemResponse
```json
{
  "mediaId": "uuid",
  "postId": "uuid",
  "authorHandle": "feedb205114",
  "mediaType": "VIDEO",
  "mediaUrl": "https://example.com/b.mp4",
  "thumbnailUrl": "https://example.com/b-thumb.jpg",
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

## 6. 피드 / 게시글 / 프로필 API

### 6-1. 전체 피드 / 팔로잉 피드
`GET /api/v1/feed?tab=all|following&cursor=&size=`

- 기본 `size=20`
- `tab=all`: 전체 피드
- `tab=following`: 내가 팔로우한 유저 + 내 글

응답 예시:
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "postId": "9426dc1b-f998-4171-ae0a-c9600d99bc06",
        "author": "feedb205114",
        "authorHandle": "feedb205114",
        "authorProfileImageUrl": null,
        "authorVerifiedBadge": false,
        "game": "PUBG",
        "content": "B video post",
        "media": [
          {
            "mediaId": "uuid",
            "mediaType": "VIDEO",
            "mediaUrl": "https://example.com/b.mp4",
            "thumbnailUrl": "https://example.com/b-thumb.jpg",
            "sortOrder": 0,
            "durationSeconds": 12
          }
        ],
        "likes": 1,
        "comments": 1,
        "shares": 0,
        "likedByMe": true,
        "mine": false,
        "createdAt": "2026-05-02T20:51:20.123+09:00"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
}
```

### 6-2. 트렌딩 게임
`GET /api/v1/feed/trending/games`

응답 예시:
```json
{
  "success": true,
  "data": [
    {
      "gameName": "PUBG",
      "postCount": 3
    }
  ]
}
```

### 6-3. 게시글 작성
`POST /api/v1/posts`

텍스트 게시글:
```json
{
  "content": "A text post",
  "gameName": "PUBG",
  "media": []
}
```

이미지 게시글:
```json
{
  "content": "A image post",
  "gameName": "PUBG",
  "media": [
    {
      "mediaType": "IMAGE",
      "mediaUrl": "https://example.com/a.jpg",
      "thumbnailUrl": null,
      "sortOrder": 0,
      "durationSeconds": null
    }
  ]
}
```

영상 게시글:
```json
{
  "content": "B video post",
  "gameName": "PUBG",
  "media": [
    {
      "mediaType": "VIDEO",
      "mediaUrl": "https://example.com/b.mp4",
      "thumbnailUrl": "https://example.com/b-thumb.jpg",
      "sortOrder": 0,
      "durationSeconds": 12
    }
  ]
}
```

### 6-4. 게시글 상세
`GET /api/v1/posts/{postId}`

### 6-5. 게시글 좋아요
`POST /api/v1/posts/{postId}/likes`

응답:
```json
{
  "success": true,
  "data": null
}
```

### 6-6. 게시글 좋아요 취소
`DELETE /api/v1/posts/{postId}/likes`

### 6-7. 댓글 작성
`POST /api/v1/posts/{postId}/comments`

요청 예시:
```json
{
  "content": "nice post"
}
```

### 6-8. 내 프로필
`GET /api/v1/users/me`

### 6-9. 사용자 프로필
`GET /api/v1/users/{handle}`

### 6-10. 프로필 posts 탭
`GET /api/v1/users/{handle}/posts?cursor=&size=`

- 게시글 단위 응답
- 텍스트 게시글도 포함

### 6-11. 프로필 media 탭
`GET /api/v1/users/{handle}/media?cursor=&size=`

- 미디어 단위 응답
- 텍스트 전용 게시글은 제외
- `IMAGE`, `VIDEO`만 내려옴

### 6-12. 팔로우
`POST /api/v1/users/{handle}/follow`

### 6-13. 언팔로우
`DELETE /api/v1/users/{handle}/follow`

## 7. 프론트 구현 시 주의사항
- 모든 피드/프로필 API는 로그인 필수입니다.
- `accessToken`은 응답 본문에서 받고, 이후 `Authorization` 헤더에 넣어야 합니다.
- `refresh_token`은 httpOnly 쿠키라 프론트 JS에서 직접 읽지 않습니다.
- `content`는 `null`일 수 있습니다.
  - 미디어 전용 게시글 가능
- `media`는 빈 배열일 수 있습니다.
  - 텍스트 전용 게시글 가능
- `profileImageUrl`은 `null`일 수 있습니다.
- `gameStats`는 현재 빈 객체 `{}` 로 내려올 수 있습니다.
- `cursor`는 프론트가 파싱하지 말고 그대로 넘기면 됩니다.
- `following` 피드는 팔로우한 유저 글과 내 글이 같이 나옵니다.
- `users/{handle}/media`는 카드 목록이 아니라 미디어 아이템 목록입니다.

## 8. 실제 검증한 예시 결과
- 테스트 계정:
  - `feeda205114` / `Test1234!`
  - `feedb205114` / `Test1234!`
- 검증 완료:
  - 회원가입
  - 로그인
  - 텍스트 / 이미지 / 영상 게시글 작성
  - 전체 피드 조회
  - 팔로우 후 팔로잉 피드 조회
  - 좋아요 / 댓글
  - 게시글 상세
  - 프로필 조회
  - 프로필 posts 탭
  - 프로필 media 탭
  - 트렌딩 게임 조회
