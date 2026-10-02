# OpenAI Moderation 검열 정책 정리

이 문서는 현재 프로젝트 코드 기준으로 게시글, 댓글, 이미지, 동영상 업로드 시 OpenAI Moderation API가 어떻게 호출되고 어떤 기준으로 콘텐츠를 허용/차단하는지 정리한 문서다.

## 적용 위치

주요 구현 파일:

- `src/main/java/com/gamerin/backend/domain/post/moderation/ContentModerationService.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/OpenAiModerationClient.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/ImageModerationPreprocessor.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/VideoFrameExtractor.java`
- `src/main/java/com/gamerin/backend/domain/post/service/PostService.java`

설정 위치:

- `src/main/resources/application.yaml`

```yaml
openai:
  api:
    key: ${OPENAI_API_KEY:}
  moderation:
    enabled: true
    model: omni-moderation-latest
    ffmpeg-path: ffmpeg
```

## 검열 대상

### 텍스트 게시글

JSON 게시글 생성 시 본문 텍스트를 저장하기 전에 검사한다.

처리 순서:

1. 게시글 본문 정규화
2. 필수값 검증
3. 텍스트 보안 검사
4. OpenAI Moderation 텍스트 검사
5. DB 저장

빈 문자열이나 공백만 있는 텍스트는 게시글 작성 자체가 거절된다.

### 댓글

댓글 작성 시 댓글 내용을 저장하기 전에 검사한다.

처리 순서:

1. 댓글 내용 정규화
2. 필수값 검증
3. 텍스트 보안 검사
4. OpenAI Moderation 텍스트 검사
5. 댓글 DB 저장

### 이미지 게시글

multipart 게시글 생성 시 이미지 파일을 저장하기 전에 검사한다.

현재 업로드 보안 정책상 서비스 업로드 이미지는 JPEG/PNG만 허용된다. Moderation API로 보낼 때는 이미지를 긴 변 최대 1024px의 JPEG data URL로 변환한다.

이미지 전처리 기준:

- 기본 변환 포맷: JPEG data URL
- 긴 변 최대 크기: 1024px
- JPEG 품질: 0.85
- OpenAI 전송 이미지 크기 제한: 20MB
- Java `ImageIO`로 읽지 못하는 WebP는 20MB 이하 원본 WebP data URL로 fallback 처리

### 동영상 게시글

OpenAI Moderation API는 동영상 자체를 직접 검사하지 않으므로, 서버에서 동영상 프레임을 이미지로 추출해 검사한다.

동영상 moderation 처리 방식:

1. `VideoMetadataService`로 동영상 길이 확인
2. 임시 디렉토리에 업로드 동영상 복사
3. `ffmpeg`로 시작, 중간, 종료 직전 프레임 추출
4. 추출된 프레임을 JPEG data URL로 변환
5. 각 프레임 이미지를 OpenAI Moderation API로 검사
6. 임시 파일과 임시 디렉토리는 best-effort로 삭제

추출 지점:

- `0.0초`
- `전체 길이 / 2`
- `전체 길이 - 0.1초`

## OpenAI 요청 방식

OpenAI 호출은 `POST /v1/moderations`로 수행한다.

기본 모델:

```text
omni-moderation-latest
```

요청 input 타입:

- 텍스트: `{ "type": "text", "text": "..." }`
- 이미지: `{ "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }`

여러 이미지가 포함된 경우 현재 구현은 요청을 나누어 보낸다.

- 이미지가 0개 또는 1개면 한 번에 요청
- 이미지가 2개 이상이면 텍스트는 매 요청에 포함하고 이미지는 한 요청당 1개씩 포함

예를 들어 텍스트 1개와 이미지 3개가 있으면 다음처럼 3번 호출한다.

```text
요청 1: 텍스트 + 이미지 1
요청 2: 텍스트 + 이미지 2
요청 3: 텍스트 + 이미지 3
```

각 요청 중 하나라도 차단 판정이 나오면 전체 업로드를 차단한다.

## 차단 정책

OpenAI 응답의 `flagged` 값이 `false`이면 통과한다.

`flagged` 값이 `true`이면 아래 정책에 따라 판단한다.

### 허용 예외 카테고리

게임 SNS 특성상 일반적인 게임 폭력 장면은 허용한다.

```text
violence
```

단, `violence`만 단독으로 flagged 된 경우에만 허용한다. 다른 차단 카테고리와 함께 flagged 되면 차단한다.

### 차단 카테고리

아래 카테고리 중 하나라도 flagged 되면 업로드 또는 저장을 차단한다.

```text
harassment
harassment/threatening
hate
hate/threatening
illicit
illicit/violent
self-harm
self-harm/intent
self-harm/instructions
sexual
sexual/minors
violence/graphic
```

차단 시 응답 상태:

```text
422 UNPROCESSABLE_ENTITY
```

응답 메시지:

```text
Content violates moderation policy: {categories}
```

### 알 수 없는 flagged 카테고리

OpenAI 응답에서 `flagged=true`인데 프로젝트가 명시적으로 허용하지 않은 카테고리가 오면 차단한다.

이 정책은 OpenAI 측에 새로운 위험 카테고리가 추가되었을 때 기본적으로 안전하게 막기 위한 fail-closed 정책이다.

예시:

- `flagged=true`, categories 없음 -> 차단
- `flagged=true`, `new-risk-category=true` -> 차단
- `flagged=true`, `violence=true`만 존재 -> 허용
- `flagged=true`, `violence=true`, `harassment=true` -> 차단

## 장애 및 설정 정책

### moderation 비활성화

`openai.moderation.enabled=false`이면 OpenAI API를 호출하지 않고 통과 처리한다.

이 경우 JSON 로그에 `moderation.openai.skipped` 이벤트가 기록된다.

### API 키 미설정

`openai.moderation.enabled=true`인데 `OPENAI_API_KEY`가 없으면 업로드를 차단한다.

응답 상태:

```text
503 SERVICE_UNAVAILABLE
```

### OpenAI API 오류

OpenAI API 요청 실패 시 기본적으로 저장을 진행하지 않는다.

오류별 처리:

- 429 Too Many Requests: `429 TOO_MANY_REQUESTS`
- OpenAI Bad Request: `502 BAD_GATEWAY`
- 응답이 비어 있음: `502 BAD_GATEWAY`
- 기타 RestClient 오류: `502 BAD_GATEWAY`

즉, moderation이 켜져 있는 운영 상태에서는 API 장애나 설정 오류가 있을 때 콘텐츠를 저장하지 않는 fail-closed 방식이다.

## 저장 차단 범위

검열이 실패하면 DB 저장과 파일 저장을 하지 않는다.

적용 범위:

- 텍스트 게시글 저장 차단
- 댓글 저장 차단
- 이미지 게시글 저장 차단
- 동영상 게시글 저장 차단
- 게시글 미디어 파일 저장 차단

multipart 게시글의 경우 파일 보안 검증과 moderation을 통과한 뒤에 저장 준비 및 DB 저장으로 넘어간다.

## 현재 정책의 의도

이 프로젝트는 게임 SNS이므로 일반적인 게임 플레이 장면에서 발생하는 비그래픽 폭력 표현은 허용한다.

하지만 다음 콘텐츠는 차단한다.

- 괴롭힘 또는 위협
- 혐오 또는 위협적 혐오
- 불법 행위 또는 폭력적 불법 행위
- 자해 관련 내용
- 성적 내용
- 미성년자 성적 내용
- 그래픽한 폭력
- OpenAI가 flagged 처리했지만 프로젝트 정책에서 명시적으로 허용하지 않은 새 위험 카테고리

## 한계

동영상은 전체 영상을 모두 검사하는 것이 아니라 시작, 중간, 종료 직전 프레임만 검사한다. 따라서 샘플링되지 않은 구간의 유해 장면을 100% 보장해서 탐지하지는 못한다.

OpenAI Moderation API는 유해 콘텐츠 판단 도구이며, 악성코드 검사 도구가 아니다. 파일 위조, 실행 파일 헤더, EICAR 테스트 시그니처, 이미지/동영상 포맷 검증 등은 별도 업로드 보안 로직에서 처리한다.
