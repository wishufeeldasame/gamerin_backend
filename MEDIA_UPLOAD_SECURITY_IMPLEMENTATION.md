# 미디어 업로드 보안 및 압축 구현 정리

## 배경

피드/게시물 업로드 로직에는 기존에 동영상 업로드 제한이 있었지만, 이미지 파일에는 개수 제한 외에 용량, 해상도, 실제 파일 포맷 검증, 압축 저장 로직이 없었다.

또한 `feature/#7_Moderation` 브랜치에는 OpenAI Moderation API 기반 유해성 검열 로직이 추가되어 있다. OpenAI Moderation API는 텍스트/이미지를 유해성 카테고리로 분류하는 용도이므로, 파일 위조, 과도한 리소스 사용, 잘못된 MIME/확장자, 압축, 메타데이터 제거 같은 업로드 보안은 서버에서 별도로 처리해야 한다.

이번 작업은 현재 `feature/#5_Feed` 브랜치에 서버 측 미디어 보안 검증과 이미지 압축 저장 계층을 추가한 것이다.

## 적용 기준

### 이미지 업로드

- 허용 포맷: JPEG, PNG
- 업로드 개수: 게시글당 최대 4장
- 원본 파일 크기: 이미지 1장당 최대 20MB
- 원본 해상도: 가로/세로 각각 최대 6000px
- 원본 픽셀 수: 최대 2400만 픽셀
- 저장 포맷: JPEG
- 저장 크기: 압축 후 최대 5MB
- 저장 해상도: 긴 변 최대 2048px

JPEG/PNG만 허용한 이유는 Java 기본 `ImageIO`로 안정적으로 디코딩, 리사이즈, JPEG 재인코딩이 가능하고, 저장 과정에서 EXIF 등 원본 메타데이터를 자연스럽게 제거할 수 있기 때문이다.

OpenAI 이미지 입력 문서상 WebP와 non-animated GIF도 이미지 입력으로 언급되지만, 서버의 안전한 압축/검증 파이프라인을 우선해 현재 서비스 업로드 기준은 더 보수적으로 잡았다.

### 동영상 업로드

- 허용 포맷: MP4, MOV, M4V
- 업로드 개수: 게시글당 최대 1개
- 원본 파일 크기: 최대 500MB
- 재생 길이: 최대 2분
- 파일 헤더: MP4 계열 `ftyp` box 확인

기존에는 `video/*` Content-Type과 확장자 중심으로 판단했지만, 이번 작업에서 WebM을 제외하고 MP4 계열 컨테이너만 허용했다. `VideoMetadataService`가 MP4/MOV/M4V의 `moov/mvhd` 메타데이터를 직접 읽는 구조이므로, 검증 가능한 포맷으로 좁힌 것이다.

### 썸네일

- 영상 썸네일은 이미지와 동일한 기준으로 검증한다.
- 저장 시에도 이미지와 동일하게 JPEG 압축 저장한다.
- 이미지 게시글에서는 기존처럼 `thumbnailFile`을 허용하지 않는다.

## 구현 파일

### `MediaUploadSecurityService`

위치: `src/main/java/com/gamerin/backend/domain/post/service/MediaUploadSecurityService.java`

역할:

- 이미지 확장자와 Content-Type 일치 여부 확인
- JPEG/PNG magic header 확인
- 이미지 디코딩 가능 여부 확인
- 이미지 해상도와 픽셀 수 제한
- 이미지를 긴 변 최대 2048px로 리사이즈
- JPEG 품질을 단계적으로 낮춰 5MB 이하로 압축
- 동영상 확장자와 Content-Type 제한
- 동영상 MP4 계열 `ftyp` 헤더 확인

주요 상수:

```java
MAX_IMAGE_FILE_SIZE_BYTES = 20MB
MAX_IMAGE_DIMENSION = 6000
MAX_IMAGE_PIXELS = 24_000_000
STORED_IMAGE_MAX_DIMENSION = 2048
MAX_STORED_IMAGE_BYTES = 5MB
JPEG_QUALITIES = 0.85, 0.75, 0.65
```

### `MediaStorageService`

위치: `src/main/java/com/gamerin/backend/domain/post/service/MediaStorageService.java`

변경 사항:

- 기존 `MultipartFile` 원본 저장 메서드는 유지했다.
- 압축 완료된 바이트 배열을 저장하기 위한 `PreparedMediaFile` record를 추가했다.
- `storePostMedia(PreparedMediaFile file)` 오버로드를 추가했다.

이미지와 썸네일은 더 이상 원본 파일명/확장자로 저장하지 않고, 서버가 생성한 UUID 파일명과 `.jpg` 확장자로 저장된다.

### `PostService`

위치: `src/main/java/com/gamerin/backend/domain/post/service/PostService.java`

변경 사항:

- `MediaUploadSecurityService`를 주입받도록 생성자 변경
- multipart 게시글 생성 시 DB 저장 전에 미디어 검증과 이미지 압축 준비 수행
- 이미지 업로드는 `prepareImage()` 결과를 저장
- 동영상 업로드는 보안 검증 후 기존 원본 저장 흐름 유지
- 영상 썸네일은 `prepareImage()` 결과를 저장
- 동영상 허용 확장자에서 `.webm` 제외

흐름:

1. 요청 content/media 정규화
2. 게시글 필수값, 이미지/동영상 혼합 여부, 개수 제한 검증
3. 이미지/동영상 파일 보안 검증
4. 이미지와 썸네일 압축 준비
5. `Post` 저장
6. 파일 저장
7. `post_media` 저장

파일 저장 실패 시 기존처럼 이미 저장된 파일을 best-effort로 정리한다.

### `PostServiceTest`

위치: `src/test/java/com/gamerin/backend/domain/post/service/PostServiceTest.java`

변경 사항:

- `PostService` 생성자 변경에 맞춰 `MediaUploadSecurityService` mock 추가
- 이미지 저장 테스트가 압축된 `PreparedMediaFile` 저장 흐름을 검증하도록 수정
- 동영상 저장 테스트는 기존 원본 `MultipartFile` 저장 흐름을 유지하도록 명시

### `MediaUploadSecurityServiceTest`

위치: `src/test/java/com/gamerin/backend/domain/post/service/MediaUploadSecurityServiceTest.java`

추가한 검증:

- PNG 이미지가 JPEG로 압축 준비되는지
- JPEG magic header로 저장되는지
- Content-Type과 확장자가 맞지 않으면 거절하는지
- 20MB 초과 이미지를 거절하는지
- MP4 계열 `ftyp` 헤더를 허용하는지
- 잘못된 동영상 헤더를 거절하는지

### `FRONTEND_FEED_INTEGRATION_SPEC_KO.md`

프론트 연동 문서에 새 업로드 기준을 반영했다.

- 이미지 JPEG/PNG 제한
- 이미지 20MB 제한
- 이미지 해상도/픽셀 수 제한
- 서버 JPEG 압축 저장 정책
- 동영상 MP4/MOV/M4V 제한

## OpenAI Moderation API와의 관계

`feature/#7_Moderation` 브랜치의 moderation 로직은 다음 역할을 가진다.

- 텍스트 게시글 본문 검열
- 댓글 텍스트 검열
- 이미지 게시글 검열
- 동영상은 시작/중간/끝 프레임을 추출해 이미지로 검열

이번 작업의 파일 보안 계층은 moderation 이전 또는 같은 사전 처리 단계에서 수행되어야 한다.

권장 순서:

1. multipart 파일 존재/개수/혼합 여부 검증
2. 파일 보안 검증
3. 이미지 압축 준비
4. OpenAI moderation 검열
5. DB 저장
6. 파일 저장

OpenAI moderation은 유해성 판단에 사용하고, 아래 항목은 서버에서 별도 검증해야 한다.

- MIME/확장자 위조
- magic header 불일치
- 이미지 디코딩 실패
- 과도한 파일 크기
- 과도한 해상도 또는 픽셀 수
- 원본 EXIF/메타데이터 제거
- 동영상 컨테이너 형식 제한
- 악성 파일/바이러스 스캔

악성 파일 검사까지 필요하면 이후 단계에서 ClamAV 같은 별도 스캐너를 붙이는 편이 좋다. OpenAI moderation은 악성코드 스캐너가 아니다.

## 테스트 결과

변경 범위 테스트:

```bash
./gradlew test --tests com.gamerin.backend.domain.post.service.PostServiceTest --tests com.gamerin.backend.domain.post.service.MediaUploadSecurityServiceTest
```

결과: 성공

전체 테스트:

```bash
./gradlew test
```

결과: 실패

실패 원인은 이번 미디어 업로드 변경이 아니라 로컬 DB의 Flyway V1 checksum mismatch다.

## Flyway V1 Checksum Mismatch 상세

전체 테스트 실패 메시지:

```text
Migration checksum mismatch for migration version 1
-> Applied to database : -1684282630
-> Resolved locally    : -1398688220
```

의미:

- Flyway는 각 마이그레이션 파일을 실행할 때 해당 파일의 checksum을 계산해 DB의 `flyway_schema_history` 테이블에 저장한다.
- 현재 로컬 DB에는 `V1__init_schema.sql`이 과거 내용 기준 checksum `-1684282630`으로 적용되어 있다.
- 그런데 현재 코드베이스의 `src/main/resources/db/migration/V1__init_schema.sql` 파일을 다시 계산한 checksum은 `-1398688220`이다.
- 즉, 이미 DB에 적용된 V1 마이그레이션 파일의 내용이 이후에 변경되었다는 뜻이다.

왜 문제가 되는가:

- Flyway는 적용 완료된 버전 마이그레이션 파일이 바뀌는 것을 위험하게 본다.
- 같은 `V1` 이름인데 개발자마다 파일 내용이 다르면, 어떤 DB는 예전 스키마로 생성되고 어떤 DB는 새 스키마로 생성될 수 있다.
- 그래서 애플리케이션 시작 시 `validate` 단계에서 오류를 내고 Spring context 초기화를 중단한다.

이번 테스트에서 실패한 지점:

- `BackendApplicationTests.contextLoads()`
- `PasswordResetFlowIntegrationTest`

둘 다 Spring context를 띄우는 테스트다. context 시작 과정에서 Flyway가 먼저 검증되고, V1 checksum mismatch로 `entityManagerFactory` 생성 전 단계에서 실패했다.

## 해결 방법

### 로컬 개발 DB만 문제인 경우

로컬 DB를 지워도 되는 상황이면 가장 단순한 방법은 DB를 초기화한 뒤 마이그레이션을 다시 적용하는 것이다.

예:

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
```

그 다음 애플리케이션 또는 테스트를 다시 실행하면 현재 코드의 V1 checksum 기준으로 새로 기록된다.

주의: 로컬 DB 데이터가 삭제된다.

### DB 데이터는 유지하고 파일 변경만 인정하려는 경우

현재 DB 스키마가 현재 V1 파일 내용과 실제로 동일하다고 확신할 수 있으면 Flyway repair를 사용할 수 있다.

```bash
./gradlew flywayRepair
```

다만 현재 프로젝트에는 Flyway Gradle plugin task가 별도로 설정되어 있지 않을 수 있다. 그 경우 애플리케이션 코드나 CLI로 repair를 수행해야 한다.

주의: repair는 `flyway_schema_history`의 checksum 기록을 현재 파일 기준으로 맞추는 작업이다. 실제 DB 스키마를 변경하지 않는다. DB 스키마와 파일 내용이 다르면 문제를 덮어버릴 수 있다.

### 팀/공유 DB에서 권장되는 방식

이미 공유되거나 적용된 마이그레이션 파일은 수정하지 않는 것이 원칙이다.

권장:

- `V1__init_schema.sql`은 과거 적용된 상태 그대로 유지
- 이후 변경 사항은 `V7__...sql`, `V8__...sql`처럼 새 버전 마이그레이션으로 추가
- 이미 변경된 V1이 있다면, 어떤 커밋에서 내용이 바뀌었는지 확인하고 팀 기준을 정한다

확인용 명령:

```bash
git log -- src/main/resources/db/migration/V1__init_schema.sql
git diff <old-commit>..<new-commit> -- src/main/resources/db/migration/V1__init_schema.sql
```

현재 mismatch는 DB 기록과 로컬 파일 checksum이 다르다는 사실만 말해준다. 어느 쪽이 “정답”인지는 DB를 초기화해도 되는 개발 환경인지, 이미 공유된 DB인지에 따라 달라진다.

## 남은 고려 사항

- `feature/#7_Moderation` 브랜치와 병합할 때 `PostService` 생성자와 multipart 생성 흐름 충돌 가능성이 있다.
- moderation 브랜치의 `ImageModerationPreprocessor`도 이미지 리사이즈/압축을 수행하므로, 이번 `MediaUploadSecurityService`와 책임을 정리하는 것이 좋다.
- 영상 moderation은 프레임 샘플링 방식이므로 전체 동영상의 모든 장면을 보장하지 않는다.
- 악성 파일 검사는 별도 보안 스캐너가 필요하다.
- 운영에서는 업로드 파일 공개 제공 전 바이러스 스캔, 저장소 격리, 다운로드 시 Content-Type 강제 설정도 고려할 만하다.
