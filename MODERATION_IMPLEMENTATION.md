# OpenAI Moderation API 적용 정리

## 적용 목표

게시물 업로드 시 저장 전에 OpenAI Moderation API로 사용자 생성 콘텐츠를 검열하도록 구현했다.

- 텍스트 게시물: 본문 텍스트 검열
- 사진 게시물: 업로드 이미지 검열
- 동영상 게시물: 동영상의 시작, 중간, 종료 지점 프레임을 추출해 이미지로 검열
- 댓글: 텍스트 검열

OpenAI 공식 문서 기준으로 Moderation API는 텍스트와 이미지를 입력으로 받을 수 있고, `omni-moderation-latest`가 신규 구현에 적합하다. 동영상 입력은 직접 지원하지 않으므로 서버에서 프레임 이미지를 추출해 검열한다.

- Moderation guide: https://platform.openai.com/docs/guides/moderation/overview
- Moderations API reference: https://platform.openai.com/docs/api-reference/moderations
- omni moderation model: https://platform.openai.com/docs/models/omni-moderation-latest

## 구현 파일

### `ContentModerationService`

위치: `src/main/java/com/gamerin/backend/domain/post/moderation/ContentModerationService.java`

- 게시물/댓글 검열의 진입점
- 텍스트, 이미지, 동영상 파일을 OpenAI Moderation 입력으로 변환
- `flagged=true` 결과가 오면 `422 UNPROCESSABLE_ENTITY`로 업로드를 차단
- 검열 대상이 없으면 API 호출 없이 통과

### `OpenAiModerationClient`

위치: `src/main/java/com/gamerin/backend/domain/post/moderation/OpenAiModerationClient.java`

- Spring `RestClient`로 `POST /v1/moderations` 호출
- 기본 모델은 `omni-moderation-latest`
- API 키 미설정 시 `503 SERVICE_UNAVAILABLE`
- OpenAI 요청 실패 시 업로드를 차단하는 fail-closed 방식

### `ImageModerationPreprocessor`

위치: `src/main/java/com/gamerin/backend/domain/post/moderation/ImageModerationPreprocessor.java`

- 이미지 원본을 검열용 JPEG data URL로 변환
- 긴 변 기준 1024px로 리사이즈
- OpenAI 이미지 입력 제한에 맞춰 20MB 초과 검열 이미지는 거절
- WebP는 Java 기본 `ImageIO`에서 읽지 못할 수 있어, 20MB 이하 원본 data URL로 fallback 처리

### `VideoFrameExtractor`

위치: `src/main/java/com/gamerin/backend/domain/post/moderation/VideoFrameExtractor.java`

- 업로드 동영상을 임시 파일로 저장
- 기존 `VideoMetadataService`로 duration을 읽고 시작/중간/종료 지점 계산
- `ffmpeg`로 3개 프레임을 추출
- 추출 프레임을 `ImageModerationPreprocessor`로 data URL 변환
- 임시 파일은 best-effort로 삭제

## 기존 흐름 연결

위치: `src/main/java/com/gamerin/backend/domain/post/service/PostService.java`

- JSON 게시물 생성: 요청 검증 후, `Post` 저장 전 텍스트 검열
- multipart 게시물 생성: 파일 개수/타입/동영상 길이 검증 후, `Post` 저장 및 파일 저장 전 검열
- 댓글 생성: 댓글 저장 전 텍스트 검열

검열이 실패하면 DB 저장과 파일 저장이 실행되지 않는다.

## 설정

위치:

- `src/main/resources/application.yaml`
- `src/main/resources/application-local.example.yaml`

추가된 설정:

```yaml
openai:
  api:
    key: ${OPENAI_API_KEY:}
  moderation:
    enabled: true
    model: omni-moderation-latest
    ffmpeg-path: ffmpeg
```

운영/로컬 환경에서 `OPENAI_API_KEY` 환경 변수를 설정해야 한다. 동영상 검열을 사용하려면 서버에 `ffmpeg`가 설치되어 있거나 `openai.moderation.ffmpeg-path`에 실행 파일 경로를 지정해야 한다.

## 테스트

추가/수정 위치: `src/test/java/com/gamerin/backend/domain/post/service/PostServiceTest.java`

추가한 검증:

- 텍스트 검열 실패 시 게시물 저장 안 함
- 이미지/미디어 검열 실패 시 게시물 저장 및 파일 저장 안 함
- 댓글 검열 실패 시 댓글 저장 안 함

실행 결과:

```bash
./gradlew test --tests com.gamerin.backend.domain.post.service.PostServiceTest
```

결과: 성공

전체 테스트도 실행했지만, 현재 로컬 DB의 Flyway 이력에서 `V1` checksum mismatch가 발생해 Spring context 테스트가 실패했다. Moderation 변경 범위 컴파일과 `PostServiceTest`는 통과했다.

```bash
./gradlew test
```

실패 원인:

```text
Migration checksum mismatch for migration version 1
```

## 운영상 주의점

- OpenAI Moderation API는 동영상을 직접 받지 않으므로 프레임 샘플링 방식은 전체 동영상의 모든 장면을 보장하지 않는다.
- 현재는 안전을 우선해 OpenAI API 장애, API 키 미설정, ffmpeg 미설정 시 업로드를 차단한다.
- 서버 배포 이미지 또는 호스트에 `ffmpeg` 설치가 필요하다.
- 필요하면 이후 단계에서 검열 결과 로그 테이블을 추가해 사용자 신고/관리자 검토와 연결할 수 있다.
