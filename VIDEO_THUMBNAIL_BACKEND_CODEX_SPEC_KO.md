# 비디오 업로드 썸네일 선택 백엔드 적용 명세서

## 목적

프론트엔드 `PostComposer.tsx`에서 비디오 업로드 시 선택된 썸네일 이미지를 백엔드로 함께 전송하고, 백엔드는 해당 썸네일을 저장한 뒤 게시글 미디어 응답의 `thumbnailUrl`로 내려준다.

이 문서는 Codex가 백엔드 적용 여부를 확인하거나 추가 작업을 이어갈 때 사용하는 작업 지시서다.

## 프론트 전송 규칙

프론트는 게시글 작성 시 `multipart/form-data`로 요청한다.

Endpoint:

```http
POST /api/v1/posts
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
```

이미지 게시글:

```text
content: string
mediaFiles: image file[]
```

비디오 게시글:

```text
content: string
mediaFiles: video file 1개
thumbnailFile: 선택된 썸네일 image file 1개
```

텍스트 게시글:

```text
content: string
```

주의:

- 프론트 명세에는 `externalLinkUrl`이 언급되지만, 현재 백엔드 피드 방향에서는 외부 링크 게시글 기능을 사용하지 않는다.
- 외부 링크를 다시 살리려면 별도 DB/DTO/응답 설계가 필요하므로 이 작업 범위에서 제외한다.

## 현재 백엔드 충족 상태

현재 백엔드는 비디오 썸네일 선택 연동에 필요한 주요 구조를 이미 가지고 있다.

### 요청 DTO

파일:

```text
src/main/java/com/gamerin/backend/domain/post/dto/request/CreateMultipartPostRequest.java
```

현재 필드:

```java
private String content;
private List<MultipartFile> mediaFiles;
private MultipartFile thumbnailFile;
```

프론트 명세의 실제 전송 필드명과 일치한다.

### 컨트롤러

파일:

```text
src/main/java/com/gamerin/backend/domain/post/controller/PostController.java
```

현재 multipart 엔드포인트:

```java
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<PostDetailResponse> createWithFiles(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @Valid @ModelAttribute CreateMultipartPostRequest request
)
```

프론트의 `FormData` 전송과 맞는다.

### 서비스 검증

파일:

```text
src/main/java/com/gamerin/backend/domain/post/service/PostService.java
```

관련 메서드:

```java
create(CustomUserPrincipal principal, CreateMultipartPostRequest request)
validateMultipartCreateRequest(String content, List<MultipartFile> mediaFiles, MultipartFile thumbnailFile)
saveUploadedMedia(Post post, List<MultipartFile> mediaFiles, MultipartFile thumbnailFile)
validateVideoConstraints(MultipartFile videoFile)
resolveMediaType(MultipartFile file)
```

현재 검증 규칙:

- 이미지 최대 4개
- 비디오 최대 1개
- 이미지와 비디오 동시 업로드 불가
- `thumbnailFile`은 비디오 업로드일 때만 허용
- `thumbnailFile`은 이미지 파일이어야 함
- 비디오 파일 크기 최대 500MB
- 비디오 길이 최대 2분
- 비디오 썸네일은 선택값이며 없어도 업로드 가능

### 저장 구조

파일:

```text
src/main/java/com/gamerin/backend/domain/post/entity/PostMedia.java
src/main/resources/db/migration/V3__add_feed_schema.sql
```

DB 컬럼:

```sql
thumbnail_url TEXT
```

엔티티 필드:

```java
private String thumbnailUrl;
```

비디오 업로드 시 `thumbnailFile`이 있으면 파일 저장 후 `thumbnailUrl`에 저장한다.

### 응답 구조

파일:

```text
src/main/java/com/gamerin/backend/domain/post/dto/response/PostMediaResponse.java
src/main/java/com/gamerin/backend/domain/post/service/PostResponseAssembler.java
```

응답 필드:

```java
UUID mediaId;
PostMediaType mediaType;
String mediaUrl;
String thumbnailUrl;
int sortOrder;
```

프론트는 `mediaType === "VIDEO"`일 때 `thumbnailUrl`을 poster 또는 카드 썸네일로 사용할 수 있다.

## Codex 적용 지시

### 1. 운영 코드 변경 필요 여부 확인

비디오 썸네일 선택만 목표라면 새 운영 코드 파일은 만들지 않는다.

확인할 것:

- `CreateMultipartPostRequest`에 `thumbnailFile`이 있는지
- `PostService`가 비디오 업로드 시 `thumbnailFile`을 저장하는지
- `PostMediaResponse`에 `thumbnailUrl`이 포함되는지
- `post_media.thumbnail_url` 컬럼이 있는지

위 항목이 모두 있으면 운영 코드 추가는 하지 않는다.

### 2. 테스트 추가 또는 유지

테스트 파일:

```text
src/test/java/com/gamerin/backend/domain/post/service/PostThumbnailUploadTest.java
```

필수 테스트 케이스:

- 비디오 + 선택 썸네일 업로드 시 `thumbnailUrl` 저장
- 비디오 + 썸네일 없음 허용
- 이미지 게시글에 `thumbnailFile` 전송 시 실패
- 비디오 썸네일이 이미지가 아니면 실패

실행 명령:

```powershell
.\gradlew.bat test --tests "com.gamerin.backend.domain.post.service.PostThumbnailUploadTest"
```

### 3. 선택 보수 사항

보수 대상:

```text
src/main/java/com/gamerin/backend/domain/post/service/MediaStorageService.java
```

권장 보수:

- 저장 경로가 업로드 디렉터리 밖으로 벗어나지 않도록 확인
- 확장자는 `.jpg`, `.png`, `.mp4`처럼 안전한 문자만 허용
- 파일 저장 실패 시 이미 저장된 파일은 `deleteQuietly`로 정리

현재 썸네일 선택 기능 자체와 직접 관련된 필수 작업은 아니다.

## 수동 테스트 절차

Swagger 또는 프론트에서 아래 순서로 확인한다.

1. 로그인 후 accessToken 확보
2. `POST /api/v1/posts` multipart 요청
3. `mediaFiles`에 비디오 1개 첨부
4. `thumbnailFile`에 이미지 1개 첨부
5. 응답의 `data.media[0].mediaType`이 `VIDEO`인지 확인
6. 응답의 `data.media[0].thumbnailUrl`이 null이 아닌지 확인
7. 해당 URL이 `/uploads/post-media/...` 형태인지 확인

예상 응답 일부:

```json
{
  "success": true,
  "data": {
    "media": [
      {
        "mediaType": "VIDEO",
        "mediaUrl": "http://localhost:8080/uploads/post-media/video.mp4",
        "thumbnailUrl": "http://localhost:8080/uploads/post-media/thumb.jpg",
        "sortOrder": 0
      }
    ]
  }
}
```

## 실패해야 하는 케이스

### 이미지 게시글에 썸네일 포함

```text
mediaFiles: image.jpg
thumbnailFile: thumb.jpg
```

예상:

```http
400 BAD_REQUEST
```

### 비이미지 썸네일

```text
mediaFiles: video.mp4
thumbnailFile: thumbnail.txt
```

예상:

```http
400 BAD_REQUEST
```

### 비디오 2개 이상

```text
mediaFiles: video1.mp4
mediaFiles: video2.mp4
```

예상:

```http
400 BAD_REQUEST
```

### 500MB 초과 비디오

예상:

```http
400 BAD_REQUEST
```

### 2분 초과 비디오

예상:

```http
400 BAD_REQUEST
```

## 프론트 Codex 전달 요약

프론트는 백엔드에 아래 필드명으로 보내면 된다.

```ts
const formData = new FormData();
formData.append("content", content);
formData.append("mediaFiles", videoFile);

if (selectedThumbnail?.file) {
  formData.append("thumbnailFile", selectedThumbnail.file);
}
```

이미지 게시글에서는 `thumbnailFile`을 보내지 않는다.

백엔드는 비디오 썸네일을 선택값으로 처리하므로, 썸네일이 없으면 `thumbnailUrl: null`로 응답할 수 있다.

## 결론

현재 백엔드는 프론트 명세의 비디오 업로드/썸네일 선택 연동을 대부분 충족한다.

필수 적용 범위는 운영 코드 신규 작성이 아니라 테스트 추가 및 회귀 검증이다.

