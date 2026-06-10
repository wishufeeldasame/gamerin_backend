# 팔로워/팔로잉 조회 프론트 연동 명세서

## 기능 개요

프로필 화면에서 팔로워 수 또는 팔로잉 수를 클릭하면 해당 사용자의 팔로워 목록 또는 팔로잉 목록을 조회한다. 목록에 표시된 사용자를 클릭하면 해당 사용자의 프로필로 이동할 수 있다.

## 인증

로그인이 필요하다.

```http
Authorization: Bearer {accessToken}
```

## 팔로워 목록 조회

```http
GET /api/v1/users/{handle}/followers
```

예시:

```http
GET /api/v1/users/gamerin/followers?size=20
```

## 팔로잉 목록 조회

```http
GET /api/v1/users/{handle}/following
```

예시:

```http
GET /api/v1/users/gamerin/following?size=20
```

## Query Params

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| cursor | string | 다음 페이지 조회용 커서. 첫 조회에서는 생략한다. |
| size | number | 한 번에 가져올 개수. 기본값 20, 최대 50. |

## 응답 형식

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "userId": "uuid",
        "handle": "player01",
        "nickname": "Player01",
        "bio": "FPS player",
        "profileImageUrl": "/uploads/profile/player01.png",
        "verifiedBadge": false,
        "isFollowing": true,
        "followedAt": "2026-06-09T02:00:00+09:00"
      }
    ],
    "nextCursor": "MjAyNi0wNi0wOVQwMjowMDowMCswOTowMHxmb2xsb3ctdXVpZA",
    "hasNext": true
  }
}
```

## 중요: Cursor 처리

`nextCursor`는 백엔드가 생성한 URL-safe Base64 문자열이다. 프론트에서는 이 값을 직접 파싱하지 말고, 다음 페이지 요청의 `cursor` 값으로 그대로 전달해야 한다.

```http
GET /api/v1/users/gamerin/followers?size=20&cursor={nextCursor}
```

`hasNext`가 `false`이면 더 이상 요청하지 않는다.

## 필드 설명

| 이름 | 설명 |
| --- | --- |
| userId | 사용자 UUID |
| handle | 프로필 이동에 사용할 공개 사용자 식별자 |
| nickname | 화면 표시 이름 |
| bio | 사용자 소개 |
| profileImageUrl | 프로필 이미지 URL |
| verifiedBadge | 인증 배지 여부 |
| isFollowing | 현재 로그인 사용자가 해당 사용자를 팔로우 중인지 여부 |
| followedAt | 팔로우 관계가 생성된 시간 |
| nextCursor | 다음 페이지 요청 시 그대로 전달할 커서 |
| hasNext | 다음 페이지 존재 여부 |

## 프론트 동작

프로필 화면에서 `followersCount` 클릭:

```http
GET /api/v1/users/{handle}/followers
```

프로필 화면에서 `followingCount` 클릭:

```http
GET /api/v1/users/{handle}/following
```

목록 아이템 클릭:

```tsx
router.push(`/profile/${user.handle}`);
```

팔로우 버튼 클릭:

```http
POST /api/v1/users/{handle}/follow
```

언팔로우 버튼 클릭:

```http
DELETE /api/v1/users/{handle}/follow
```
