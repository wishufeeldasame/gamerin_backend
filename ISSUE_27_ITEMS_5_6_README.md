# Issue #27 5번·6번 보완 및 미디어 업로드 정책

## 1. 문서 목적

이 문서는 Issue #27의 다음 항목에 대한 로컬 백엔드 구현과 운영 정책을 하나로 정리한다.

- 5번: 전역 `RuntimeException` 처리 과정의 내부 정보 노출
- 6번: 대용량 multipart 요청과 요청 흐름 안의 FFmpeg 실행으로 발생하는 운영 위험
- 피드 이미지·GIF·영상 업로드 정책
- Docker/Nginx와 백엔드 제한의 정합성
- 테스트 결과와 운영 환경에서 별도로 수행해야 하는 작업

현재 구현은 로컬 브랜치 `fix/video-upload-500mb-policy`에 있으며 아직 커밋되지 않았다. 원격 `main`에는 PR #28 리버트가 반영되어 있으므로, 실제 배포 적용을 위해서는 최신 `main` 기준으로 변경사항을 다시 커밋하고 PR을 머지해야 한다.

## 2. 최종 정책 요약

### 게시물 첨부 정책

| 구분 | 정책 |
| --- | --- |
| 정적 이미지 | JPG, JPEG, PNG |
| 애니메이션 이미지 | GIF |
| 영상 | MP4, MOV, M4V |
| 게시물당 이미지 | 최대 4개 |
| 게시물당 영상 | 최대 1개 |
| 이미지·영상 혼합 | 허용하지 않음 |
| 개별 이미지 용량 | 최대 20MiB |
| 개별 영상 용량 | 최대 500MiB, `524288000`바이트 |
| 영상 길이 | 최대 2분 |
| multipart 파일 제한 | 500MB |
| multipart 요청 제한 | 530MB |

정확히 500MiB인 영상은 허용하며, 1바이트라도 초과하면 애플리케이션 검증에서 거부한다. multipart 요청에는 파일 이외의 게시물 내용과 boundary가 포함되므로 전체 요청 제한은 530MB로 유지한다.

### GIF 전용 정책

피드 GIF는 단순 확장자 검사가 아니라 애니메이션 전체를 디코딩하고 재인코딩한다.

| 항목 | 제한 |
| --- | --- |
| 파일 확장자 | `.gif` |
| Content-Type | `image/gif` |
| 매직바이트 | `GIF87a` 또는 `GIF89a` |
| 파일 크기 | 최대 20MiB |
| 프레임 수 | 2~300프레임 |
| 가로·세로 | 각각 최대 6000px |
| 프레임당 픽셀 | 최대 24메가픽셀 |
| 전체 애니메이션 픽셀 | 최대 1억 2천만 픽셀 |

- 단일 프레임 GIF는 움짤 전용 정책에 따라 거부한다.
- 프레임 크기와 위치는 이미지 디코딩 전에 메타데이터로 먼저 검사한다.
- 모든 프레임을 디코딩하고 disposal 방식을 반영해 합성한다.
- 검증된 프레임만 서버에서 다시 GIF로 인코딩하므로 원본 파일명이나 원본 바이트를 그대로 저장하지 않는다.
- 모더레이션은 처음·중간·마지막 합성 프레임을 각각 이미지로 검사한다.
- 프로필 이미지에는 기존 정책대로 JPG, JPEG, PNG만 허용하며 GIF는 허용하지 않는다.

## 3. 5번: 전역 RuntimeException 처리

### 기존 문제

- `e.printStackTrace()`로 예외를 직접 출력
- `e.getMessage()`를 클라이언트 응답에 그대로 포함
- 데이터베이스 메시지, 내부 경로, 토큰 등 민감한 정보가 노출될 가능성
- 일반 서버 오류를 HTTP `400 Bad Request`로 반환

### 변경 정책

- 예상하지 못한 `RuntimeException`은 HTTP `500 Internal Server Error`로 반환
- 상세 예외와 스택 트레이스는 SLF4J 서버 로그에만 기록
- 클라이언트에는 고정된 일반 메시지만 반환
- `JsonLogContext`에도 일반화된 실패 사유만 기록
- `IllegalArgumentException`, `ResponseStatusException` 등 별도 핸들러의 기존 동작은 유지

응답 형식:

```json
{
  "success": false,
  "message": "서버 처리 중 오류가 발생했습니다.",
  "data": null
}
```

## 4. 6번: 대용량 업로드 및 FFmpeg 운영 방어

### 요청 처리 흐름

```text
클라이언트
  -> Nginx가 요청 크기와 요청 빈도 검사
  -> Spring Security 인증·인가
  -> 게시물 multipart 업로드 슬롯 획득
  -> Spring multipart 파싱
  -> 파일 형식·용량·매직바이트 검증
  -> 미디어 모더레이션
  -> tmp 여유 공간 검사
  -> FFmpeg 슬롯 획득 및 영상 최적화
  -> 검증된 결과 저장
  -> 임시 파일과 슬롯 정리
```

### multipart 업로드 동시성

`POST /api/v1/posts`의 multipart 요청에 공정한 `Semaphore`를 적용한다.

- 기본 동시 처리 수: 1개
- 기본 슬롯 대기시간: 5000ms
- 인증과 인가가 완료된 요청에만 적용
- 슬롯 포화: HTTP `429 Too Many Requests`
- 대기 중 인터럽트: HTTP `503 Service Unavailable`
- JSON 응답 형식과 `JsonLogContext` 기록 유지
- 획득한 슬롯만 `finally`에서 반환

이미지 게시물도 multipart 메모리·디스크 자원을 사용하므로 게시물 multipart 요청 전체에 동일한 제한을 적용한다.

### FFmpeg 동시성

- 기본 동시 실행 수: 1개
- 기본 슬롯 대기시간: 5000ms
- 슬롯 포화: HTTP `429 Too Many Requests`
- 인터럽트: HTTP `503 Service Unavailable`
- 성공·실패·타임아웃 여부와 관계없이 `finally`에서 슬롯 반환

이 제한은 단일 백엔드 프로세스 안에서만 동작한다. 백엔드 인스턴스가 여러 대라면 Redis 기반 분산 락이나 별도 작업 큐가 필요하다.

### 공통 FFmpeg 실행기

영상 최적화와 영상 모더레이션이 `FfmpegProcessRunner`를 함께 사용한다.

- FFmpeg 실행과 동시에 가상 스레드가 표준 출력과 오류 출력을 계속 비움
- 출력 파이프가 가득 차 프로세스가 멈추는 교착 상태 방지
- 전체 출력은 소비하지만 서버 로그용으로 최대 4000자만 보관
- 영상 최적화 timeout 기본값: 60000ms
- 모더레이션 프레임 추출 timeout 기본값: 15000ms
- timeout 발생 시 정상 종료 요청 후 필요하면 강제 종료
- 프로세스 종료 후 출력 수집 작업 정리
- FFmpeg 상세 출력은 클라이언트 응답에 포함하지 않음

빠른 remux 명령이 일반적인 명령 실패로 종료된 경우에만 저비용 트랜스코딩을 한 번 재시도한다. timeout, 인터럽트, FFmpeg 미설치, 출력 수집 실패에는 트랜스코딩을 재시도하지 않는다.

### 임시 저장 공간

영상 파일을 tmp로 복사하기 전에 다음 기준으로 여유 공간을 검사한다.

```text
필요 공간 = 입력 영상 크기 × 2 + 예비 공간
기본 예비 공간 = 512MiB
```

500MiB 영상을 처리하려면 기본적으로 약 1.5GiB 이상의 tmp 여유 공간이 필요하다. 영상 모더레이션 프레임 추출과 영상 최적화 모두 복사 전에 같은 검사를 적용한다.

- 공간 부족: HTTP `503 Service Unavailable`
- 파일 시스템 용량 확인 실패: HTTP `503 Service Unavailable`
- 실제 디스크 경로와 상세 원인은 서버 로그에만 기록
- 계산 오버플로는 공간 부족으로 처리

이 검사는 요청 시점의 여유 공간을 확인하는 방어 코드다. 운영 서버의 지속적인 디스크 모니터링과 알림을 대체하지 않는다.

## 5. 오류 응답 정책

| 상황 | HTTP 상태 | 사용자 메시지 원칙 |
| --- | --- | --- |
| 예상하지 못한 RuntimeException | 500 | 일반화된 서버 오류 |
| multipart 최대 크기 초과 | 413 | 업로드 크기 초과 안내 |
| 영상 정책 용량·길이 초과 | 400 | 설정된 정책 위반 안내 |
| GIF 형식·프레임 정책 위반 | 400 | GIF 검증 실패 안내 |
| 업로드 또는 FFmpeg 슬롯 포화 | 429 | 잠시 후 재시도 안내 |
| tmp 공간 부족·확인 실패 | 503 | 비디오 처리 공간 부족 안내 |
| FFmpeg timeout·미설치·인터럽트 | 503 | 비디오 처리 서비스 사용 불가 안내 |
| FFmpeg 명령 실패 | 400 | 비디오 파일 처리 불가 안내 |

내부 FFmpeg 출력, 실행 경로, 임시 파일 경로와 예외 원문은 응답에 포함하지 않는다.

## 6. 환경변수

```text
SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=500MB
SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE=530MB

APP_MEDIA_VIDEO_MAX_FILE_SIZE_BYTES=524288000
APP_MEDIA_UPLOAD_MAX_CONCURRENCY=1
APP_MEDIA_UPLOAD_ACQUIRE_TIMEOUT_MS=5000
APP_MEDIA_VIDEO_TEMP_STORAGE_RESERVE_BYTES=536870912

APP_MEDIA_VIDEO_OPTIMIZATION_MAX_CONCURRENCY=1
APP_MEDIA_VIDEO_OPTIMIZATION_ACQUIRE_TIMEOUT_MS=5000
APP_MEDIA_VIDEO_OPTIMIZATION_TIMEOUT_MS=60000

OPENAI_MODERATION_FFMPEG_TIMEOUT_MS=15000
```

설정값이 없으면 위 기본값을 사용한다. 운영 환경에서는 서버 CPU, tmp 용량과 사용자 트래픽을 기준으로 조정한다.

## 7. Docker/Nginx 정합성

공용 Docker 저장소는 수정하지 않았다. 확인 당시 로컬 Docker 저장소는 원격 `main`과 동일한 `c56287443091a0bbc0c2dfe714f2579d02048da9`이며 작업 트리가 깨끗하다.

현재 Nginx 정책:

```nginx
location = /api/v1/posts {
    client_max_body_size 530m;
    limit_req zone=post_upload burst=5 nodelay;
    proxy_pass http://backend:8080;
}
```

정합성:

```text
Nginx 게시물 요청 제한: 530m
Spring multipart 파일 제한: 500MB
Spring multipart 요청 제한: 530MB
백엔드 영상 파일 제한: 500MiB
```

Nginx는 백엔드보다 앞에서 요청을 수신하므로 530MiB에 가까운 요청 본문을 먼저 버퍼링할 수 있다. 백엔드의 Semaphore와 tmp 검사는 Nginx 수신 비용 이후의 애플리케이션 자원을 보호한다.

## 8. 주요 변경 파일

### 예외 처리

- `src/main/java/com/gamerin/backend/global/exception/GlobalExceptionHandler.java`
- `src/test/java/com/gamerin/backend/global/exception/GlobalExceptionHandlerTest.java`

### 업로드 및 FFmpeg

- `src/main/java/com/gamerin/backend/domain/post/filter/PostUploadConcurrencyFilter.java`
- `src/main/java/com/gamerin/backend/domain/post/filter/PostUploadConcurrencyConfiguration.java`
- `src/main/java/com/gamerin/backend/domain/post/service/FfmpegProcessRunner.java`
- `src/main/java/com/gamerin/backend/domain/post/service/VideoOptimizationService.java`
- `src/main/java/com/gamerin/backend/domain/post/service/VideoTemporaryStorageGuard.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/VideoFrameExtractor.java`
- `src/main/java/com/gamerin/backend/global/security/config/SecurityConfig.java`

### GIF 및 미디어 정책

- `src/main/java/com/gamerin/backend/domain/post/service/AnimatedGifProcessor.java`
- `src/main/java/com/gamerin/backend/domain/post/service/MediaUploadSecurityService.java`
- `src/main/java/com/gamerin/backend/domain/post/service/PostService.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/ContentModerationService.java`
- `src/main/java/com/gamerin/backend/domain/post/moderation/ImageModerationPreprocessor.java`

### 설정

- `src/main/resources/application.yaml`
- `src/main/resources/application-prod.yaml`

## 9. 검증 결과

```text
변경 관련 테스트: 81개 성공
백엔드 전체 테스트: 170개 성공
실패: 0개
오류: 0개
건너뜀: 0개
Gradle build: 성공
전체 테스트 반복 실행: 2회 연속 성공
```

주요 검증 항목:

- RuntimeException 내부 메시지와 원인 예외 미노출
- HTTP 500 및 공통 응답 형식
- 정확히 500MiB인 영상 허용
- 500MiB를 1바이트 초과한 영상 거부
- 설정값과 기본값 주입
- multipart 및 FFmpeg 동시성 제한
- 인터럽트 상태와 Semaphore 소유권 보존
- tmp 필요 공간 경계값과 오버플로
- FFmpeg 대량 출력 교착 방지
- 파이프 용량을 초과한 출력 후 정지하는 프로세스의 timeout 종료
- FFmpeg 실행 파일 부재와 호출 스레드 인터럽트
- FFmpeg timeout 테스트가 3초 안에 종료
- timeout 시 트랜스코딩 미재시도
- remux 명령 실패 시에만 트랜스코딩 1회 재시도
- FFmpeg 상세 출력 클라이언트 미노출
- 두 multipart 요청의 실제 슬롯 경합과 슬롯 복구
- GIF 애니메이션 프레임 유지
- 단일 프레임·Content-Type 불일치·매직바이트 위장 GIF 거부
- 정확히 300프레임 허용과 301프레임 거부
- 정확히 20MiB 허용과 1바이트 초과 거부
- 대소문자 확장자·Content-Type 처리
- 잘린 GIF와 무작위 손상 GIF corpus 거부
- 프레임 수, 해상도와 전체 애니메이션 픽셀 폭탄 거부
- GIF 처음·중간·마지막 프레임 모더레이션
- 영상 모더레이션 3프레임 생성과 tmp 디렉터리 정리
- 피드 서비스의 GIF IMAGE 저장 경로
- 지원하지 않는 WebP 거부
- 프로필 GIF 거부

테스트의 500MiB 경계값은 `MultipartFile#getSize()`를 모킹해 검증했다. 실제 500MiB 파일을 Nginx부터 백엔드 저장까지 전달하는 운영 E2E는 별도로 수행해야 한다.

### 위험 기반 추가 검증 결과

| 위험 영역 | 검증한 상황 | 결과 |
| --- | --- | --- |
| FFmpeg 교착 | 250KB 이상 출력을 낸 뒤 계속 실행 | 출력 소비 후 제한시간 안에 종료 |
| FFmpeg timeout | 10초 대기 프로세스에 100ms 제한 적용 | 3초 이내 강제 종료 |
| FFmpeg 실행 장애 | 존재하지 않는 실행 파일 | 일반화된 서비스 불가 오류 |
| FFmpeg 인터럽트 | 이미 인터럽트된 호출 스레드 | 인터럽트 보존 및 자원 정리 |
| FFmpeg fallback | remux 실패, timeout, 서비스 장애 | remux 명령 실패에만 1회 재시도 |
| 업로드 경합 | 첫 요청이 슬롯을 점유한 중 두 번째 요청 | 두 번째 요청 429, 첫 요청 종료 후 슬롯 복구 |
| GIF 경계 | 2·300·301프레임 | 2·300 허용, 301 거부 |
| GIF 크기 | 20MiB 및 1바이트 초과 | 경계 허용, 초과 거부 |
| GIF 위장 | MIME 불일치, HTML 바이트, 잘린 파일 | 모두 400 거부 |
| GIF 파서 내구성 | 고정 시드 무작위 손상 파일 25개 | 통제된 오류로 거부 |
| GIF 자원 고갈 | 초대형 논리 화면과 전체 픽셀 초과 | 캔버스 할당 전에 거부 |
| GIF 검열 우회 | 서로 다른 처음·중간·마지막 프레임 | 세 합성 프레임 모두 모더레이션 입력 |
| 임시 파일 | 영상 모더레이션 성공·실패 | 임시 디렉터리 정리 |

## 10. 운영 잔여사항

백엔드 코드에서 해결된 항목과 별개로 다음 운영 작업은 남아 있다.

- 실제 인증 사용자의 500MiB 업로드 E2E
- 호스트 및 Docker 데이터 디스크 모니터링
- tmp 사용량 임계치 알림
- 컨테이너 CPU·메모리 제한 확인
- Nginx 로그와 Docker 로그 순환
- 동시 업로드와 FFmpeg 부하 테스트
- 다중 백엔드 인스턴스 운영 시 전역 동시성 제한
- 업로드 지연이나 429 응답이 반복될 경우 비동기 작업 큐 도입

권장 비동기 구조:

```text
영상 업로드
  -> DB에 PROCESSING 상태 저장
  -> 제한된 작업 큐 등록
  -> 별도 FFmpeg Worker 처리
  -> COMPLETED 또는 FAILED 상태 반영
```

## 11. 최종 판정

- Issue #27 5번 백엔드 조치: 로컬 구현 완료
- Issue #27 6번 백엔드 방어: 로컬 구현 완료
- 500MiB 영상 및 530MB 요청 정책: 적용 완료
- 업로드·FFmpeg 동시성 제한: 적용 완료
- FFmpeg 출력 교착·timeout·오류 노출 방지: 적용 완료
- GIF 움짤 업로드·재인코딩·다중 프레임 검열: 적용 완료
- 공용 Docker 변경: 없음
- 전체 테스트와 빌드: 성공
- 원격 `main` 반영: 미완료
- 운영 E2E와 인프라 모니터링: 후속 작업
