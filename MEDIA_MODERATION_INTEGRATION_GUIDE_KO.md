# 미디어 보안 검증과 OpenAI Moderation 통합 가이드

## 한 줄 요약

파일 보안 검증은 “이 파일을 서버가 받아도 되는가?”를 확인하고, OpenAI Moderation은 “이 콘텐츠를 사용자에게 보여줘도 되는가?”를 확인한다.

둘은 역할이 다르기 때문에 둘 다 필요하다.

## 왜 둘 다 필요한가

OpenAI Moderation API는 유해 콘텐츠를 판단하는 도구다.

예를 들면 이런 것을 본다.

- 폭력적 내용
- 성적 내용
- 혐오 표현
- 괴롭힘
- 자해 관련 내용

하지만 OpenAI Moderation API가 아래 문제까지 대신 막아주는 것은 아니다.

- 파일 확장자 위조
- MIME 타입 위조
- 이미지 파일인 척하는 다른 파일
- 너무 큰 이미지로 서버 메모리를 많이 쓰는 공격
- 너무 큰 해상도 이미지
- EXIF 같은 원본 메타데이터
- 동영상 컨테이너 형식 문제
- 바이러스나 악성 파일

그래서 서버에는 별도 파일 보안 검증이 있어야 한다.

## 역할 나누기

### `MediaUploadSecurityService`

이 서비스는 파일 자체를 검사한다.

질문으로 표현하면:

> 이 파일을 우리 서버가 받아도 안전한가?

담당하는 일:

- 이미지가 JPEG/PNG인지 확인
- 확장자와 Content-Type이 맞는지 확인
- 파일 헤더가 실제 이미지/동영상 형식과 맞는지 확인
- 이미지가 실제로 디코딩되는지 확인
- 이미지 용량과 해상도가 너무 크지 않은지 확인
- 저장용 이미지를 JPEG로 압축
- 동영상이 MP4/MOV/M4V인지 확인

### `ContentModerationService`

이 서비스는 콘텐츠 내용을 검사한다.

질문으로 표현하면:

> 이 글, 이미지, 동영상을 서비스에 노출해도 되는가?

담당하는 일:

- 게시글 텍스트를 OpenAI Moderation API로 검사
- 댓글 텍스트를 OpenAI Moderation API로 검사
- 이미지를 OpenAI Moderation API로 검사
- 동영상은 일부 프레임을 이미지로 뽑아서 검사

## 가장 중요한 처리 순서

multipart 게시글 업로드는 아래 순서로 처리하면 된다.

```text
1. 요청값 정리
2. 게시글 내용 또는 미디어가 있는지 확인
3. 이미지/동영상 개수 제한 확인
4. 이미지와 동영상을 같이 올렸는지 확인
5. 파일 보안 검증
6. 저장용 이미지 압축 준비
7. OpenAI Moderation 검열
8. 게시글 DB 저장
9. 파일 저장
10. post_media DB 저장
```

짧게 말하면:

```text
파일 보안 검증 -> OpenAI 검열 -> DB 저장 -> 파일 저장
```

이 순서가 좋은 이유:

- 이상한 파일은 OpenAI API 호출 전에 차단된다.
- 유해 콘텐츠는 DB와 파일 저장 전에 차단된다.
- 서버에 저장되는 이미지는 압축된 안전한 JPEG가 된다.

## `PostService`에서 맞춰야 하는 모양

최종적으로 `PostService`는 두 서비스를 모두 가지고 있어야 한다.

```java
private final MediaUploadSecurityService mediaUploadSecurityService;
private final ContentModerationService contentModerationService;
```

생성자에도 두 서비스가 모두 들어가야 한다.

```java
public PostService(
        UserRepository userRepository,
        PostRepository postRepository,
        PostMediaRepository postMediaRepository,
        PostLikeRepository postLikeRepository,
        PostBookmarkRepository postBookmarkRepository,
        PostCommentRepository postCommentRepository,
        PostShareRepository postShareRepository,
        PostResponseAssembler postResponseAssembler,
        MediaStorageService mediaStorageService,
        VideoMetadataService videoMetadataService,
        MediaUploadSecurityService mediaUploadSecurityService,
        ContentModerationService contentModerationService
) {
    this.userRepository = userRepository;
    this.postRepository = postRepository;
    this.postMediaRepository = postMediaRepository;
    this.postLikeRepository = postLikeRepository;
    this.postBookmarkRepository = postBookmarkRepository;
    this.postCommentRepository = postCommentRepository;
    this.postShareRepository = postShareRepository;
    this.postResponseAssembler = postResponseAssembler;
    this.mediaStorageService = mediaStorageService;
    this.videoMetadataService = videoMetadataService;
    this.mediaUploadSecurityService = mediaUploadSecurityService;
    this.contentModerationService = contentModerationService;
}
```

## 텍스트 게시글 처리

텍스트 게시글은 저장 전에 OpenAI 검열을 먼저 한다.

```java
validateCreateRequest(content);
contentModerationService.assertTextAllowed(content);

Post post = Post.create(user, content);
Post savedPost = postRepository.save(post);
```

순서가 중요한 이유:

- 검열에 걸린 텍스트는 DB에 저장하지 않는다.

## 댓글 처리

댓글도 저장 전에 OpenAI 검열을 먼저 한다.

```java
if (content == null) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment content is required.");
}

contentModerationService.assertTextAllowed(content);

PostComment savedComment = postCommentRepository.save(PostComment.create(post, user, content));
```

## 파일 게시글 처리

파일 게시글은 아래 모양으로 맞추면 된다.

```java
validateMultipartCreateRequest(content, mediaFiles, thumbnailFile);

PreparedMediaUpload preparedMediaUpload = prepareMediaUpload(mediaFiles, thumbnailFile);

contentModerationService.assertPostAllowed(content, mediaFiles);

Post post = Post.create(user, content);
Post savedPost = postRepository.save(post);

if (!preparedMediaUpload.isEmpty()) {
    saveUploadedMedia(savedPost, preparedMediaUpload);
}
```

여기서 각 줄의 의미는 이렇다.

```java
validateMultipartCreateRequest(content, mediaFiles, thumbnailFile);
```

업로드 기본 규칙을 확인한다.

- 내용 또는 파일이 있는가
- 이미지는 최대 4장인가
- 동영상은 최대 1개인가
- 이미지와 동영상을 섞지 않았는가
- 동영상 길이와 용량이 제한 안에 있는가
- 파일 헤더와 형식이 안전한가

```java
PreparedMediaUpload preparedMediaUpload = prepareMediaUpload(mediaFiles, thumbnailFile);
```

저장할 파일을 미리 준비한다.

- 이미지는 압축된 JPEG로 준비한다.
- 동영상은 원본 `MultipartFile`을 유지한다.
- 썸네일은 압축된 JPEG로 준비한다.

```java
contentModerationService.assertPostAllowed(content, mediaFiles);
```

OpenAI Moderation API로 유해성 검사를 한다.

이 검사가 실패하면 DB 저장과 파일 저장을 하지 않는다.

## 이미지 압축과 moderation 이미지 전처리는 왜 둘 다 있나

둘 다 이미지를 리사이즈하거나 압축해서 헷갈릴 수 있다.

하지만 목적이 다르다.

### `MediaUploadSecurityService`

저장용 이미지를 만든다.

- 사용자가 나중에 보는 이미지
- 서버에 남는 이미지
- 긴 변 최대 2048px
- 최대 5MB
- JPEG 저장

### `ImageModerationPreprocessor`

OpenAI에 보낼 이미지를 만든다.

- 검열 API에 보낼 임시 입력
- 서버에 저장하지 않음
- 긴 변 최대 1024px
- data URL 생성

그래서 지금은 둘을 억지로 합치지 않는 것이 좋다.

나중에 중복을 줄이고 싶으면 공통 이미지 처리만 별도 서비스로 뺄 수 있다.

예:

```text
ImageProcessingService
```

공통으로 담당할 수 있는 일:

- 이미지 디코딩
- 해상도 확인
- 리사이즈
- JPEG 인코딩

그 위에서 두 서비스가 각자 정책을 적용하면 된다.

```text
MediaUploadSecurityService
-> 저장용 정책 적용

ImageModerationPreprocessor
-> OpenAI 전송용 정책 적용
```

## 병합할 때 충돌이 나면 이렇게 고른다

### 1. `PostService` 생성자 충돌

두 서비스 모두 살린다.

남겨야 하는 것:

```java
MediaUploadSecurityService mediaUploadSecurityService
ContentModerationService contentModerationService
```

### 2. 텍스트 게시글 생성 충돌

저장 전에 moderation 호출을 둔다.

```java
validateCreateRequest(content);
contentModerationService.assertTextAllowed(content);
postRepository.save(...);
```

### 3. 댓글 생성 충돌

저장 전에 moderation 호출을 둔다.

```java
contentModerationService.assertTextAllowed(content);
postCommentRepository.save(...);
```

### 4. multipart 게시글 생성 충돌

이 순서로 맞춘다.

```text
validateMultipartCreateRequest
prepareMediaUpload
contentModerationService.assertPostAllowed
postRepository.save
saveUploadedMedia
```

### 5. 파일 저장 충돌

이미지와 썸네일은 압축된 파일을 저장한다.

```java
mediaStorageService.storePostMedia(preparedMediaFile);
```

동영상은 기존 원본 파일을 저장한다.

```java
mediaStorageService.storePostMedia(videoMultipartFile);
```

## 테스트에서 맞춰야 하는 것

`PostServiceTest`에는 두 mock이 모두 필요하다.

```java
@Mock
private MediaUploadSecurityService mediaUploadSecurityService;

@Mock
private ContentModerationService contentModerationService;
```

확인해야 할 테스트:

- 텍스트 moderation 실패 시 게시글 저장 안 됨
- 댓글 moderation 실패 시 댓글 저장 안 됨
- 이미지 moderation 실패 시 게시글 저장 안 됨
- 이미지 업로드 성공 시 `PreparedMediaFile` 저장
- 동영상 업로드 성공 시 `MultipartFile` 저장
- 썸네일 업로드 성공 시 `PreparedMediaFile` 저장

## 최종 체크리스트

병합 후 아래 항목을 확인하면 된다.

- `PostService`에 두 서비스가 모두 주입되어 있는가
- 텍스트 게시글은 저장 전에 moderation을 하는가
- 댓글은 저장 전에 moderation을 하는가
- 파일 게시글은 파일 보안 검증 후 moderation을 하는가
- moderation 실패 시 DB 저장이 안 되는가
- 이미지 저장은 압축된 JPEG로 되는가
- 동영상 저장은 기존 원본 저장 흐름을 유지하는가
- 썸네일은 압축된 JPEG로 저장되는가
- `PostServiceTest` 생성자가 깨지지 않았는가
- `application.yaml`에 OpenAI 설정이 유지되어 있는가

## 가장 쉬운 기억법

```text
파일이 안전한지 먼저 본다.
그 다음 내용이 유해한지 본다.
둘 다 통과하면 저장한다.
```

