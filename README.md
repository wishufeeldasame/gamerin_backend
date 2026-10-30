# GamerIN Backend

- 전준범
- 서장호
- 방경식
- 이상혁
- 김신의

GamerIN 서비스의 백엔드 API 서버다. 피드·게시물, 팔로우·북마크, DM(SSE), 멘토링, 신고/관리자, 게임 전적 연동(PUBG·R6·Riot), 카카오페이 마일리지 충전을 제공한다.

> 이 문서는 `build.gradle`, `application*.yaml`, controller 매핑, `Dockerfile`, `db/migration`을 기준으로 작성했다. 코드에서 확인하지 못한 항목은 `확인 필요`로 표시한다.

## 기술 스택

| 구분 | 내용 |
| --- | --- |
| 언어/런타임 | Java 21 (Gradle toolchain) |
| 프레임워크 | Spring Boot 3.5.12 (Web MVC, Security, Data JPA, Validation, Mail, OAuth2 Client) |
| 인증 | JWT (jjwt 0.12.5), Google OAuth2 |
| DB | PostgreSQL (런타임), Flyway 마이그레이션 |
| API 문서 | springdoc-openapi 2.8.17 (Swagger UI) |
| 테스트 | JUnit Platform, Spring Boot Test, Spring Security Test, H2 (테스트 전용) |
| 미디어 | FFmpeg (동영상 최적화·검열용 프레임 추출) |
| 외부 연동 | OpenAI Moderation, PUBG, R6Data, Riot, KakaoPay, SMTP 메일 |

## 도메인 구조

소스 루트: `src/main/java/com/gamerin/backend/`

- `domain/` : admin, auth, bookmark, follow, game, hashtag, mention, mentoring, message, notification, payment, post, pubg, r6, report, repost, riot, search, user
  - 각 도메인은 controller, service, repository, entity, dto 계층을 따른다.
  - 피드는 `domain/post/`, 마일리지는 `domain/user/`에 있다.
- `global/` : config, exception, logging, response, security, seed

## 주요 API 개요

controller는 모두 `/api/v1/...` 아래에 있으며, 도메인 이름과 URL이 항상 일치하지는 않는다. 아래는 대표 엔드포인트다. 전체 목록은 controller 소스나 로컬 Swagger에서 확인한다.

| Prefix | Controller | 대표 엔드포인트 |
| --- | --- | --- |
| `/api/v1/auth` | AuthController | `POST /signup`, `POST /social-signup`, `POST /login`, `POST /refresh`, `POST /logout`, `POST /find-id`, `POST /find-password`, `POST /reset-password`, `GET /me`, `GET /availability/handle` |
| `/api/v1/users` | UserController | `GET/PATCH /me`, `POST /me/profile-images`, `GET /me/bookmarks`, `GET /{handle}`, `GET /{handle}/posts`, `GET /{handle}/media` |
| `/api/v1/users` | FollowController | `POST/DELETE /{handle}/follow`, `GET /{handle}/followers`, `GET /{handle}/following` |
| `/api/v1/feed` | FeedController | `GET /` |
| `/api/v1/posts` | PostController | `POST /` (JSON 또는 multipart), `GET/DELETE /{postId}`, `POST/DELETE /{postId}/likes`, `/reposts`, `/bookmarks`, `POST /{postId}/shares`, `POST/GET /{postId}/comments`, `DELETE /{postId}/comments/{commentId}` |
| `/api/v1/bookmark-collections` | BookmarkCollectionController | `POST/GET /`, `GET/PATCH/DELETE /{collectionId}`, `GET /{collectionId}/bookmarks`, `PUT/DELETE /{collectionId}/bookmarks/{postId}` |
| `/api/v1/hashtags` | HashtagController | `GET /`, `GET /{name}/posts` |
| `/api/v1/search` | SearchController | `GET /`, `/accounts`, `/posts`, `/hashtags` |
| `/api/v1/notifications` | NotificationController | `GET /`, `GET /unread-count`, `PATCH /{notificationId}/read`, `PATCH /read-all` |
| `/api/v1/messages` | MessageController | `GET/POST /conversations`, `GET /conversations/{conversationId}/messages`, `PATCH /conversations/{conversationId}/read`, `GET /stream` (SSE), `POST /stream-token`, `GET /attachments/{attachmentId}`, `GET /recipients`, `POST /share-post` |
| `/api/v1/mentoring` | MentoringController | `/mentors`, `/programs`, `/applications`(accept/reject/start/complete/finish/cancel), `/reviews` |
| `/api/v1/mileage` | MileageController | `GET /me/balance`, `GET /me/transactions` |
| `/api/v1/payments` | PaymentController | `GET /products`, `POST /kakao/ready`, `POST /kakao/approve`, `GET /`, `GET /{orderId}`, `POST /{orderId}/cancel` (`kakaopay.cancel-enabled` 플래그) |
| `/api/v1/games` | GameController | `GET /` |
| `/api/v1/pubg` | PubgController | `POST /connect`, `GET /me`, `DELETE /disconnect` |
| `/api/v1/r6` | R6Controller | `POST /connect`, `GET /me`, `POST /me/refresh`, `DELETE /disconnect` |
| `/api/v1/riot` | RiotController | `POST /connect`, `GET /lol/me`, `DELETE /disconnect` |
| `/api/v1/reports` | ReportController | `GET /reasons`, `GET /my`, `POST /` |
| `/api/v1/admin/**` | Admin*Controller | dashboard(`/dashboard/stats`, `/audit-logs`), `/users`, `/reports`, `/contents`, `/settings`, `/mentoring`, 제재(`/users/{userId}/penalties`, `/penalties/active`) |

## 응답 형식

- 성공: `{ "success": true, "data": ... }` (`global/response/ApiResponse.java`)
- 커서 목록: `data` 내부에 `{ "items": [...], "nextCursor": ..., "hasNext": ... }` (`CursorPageResponse.java`)
- 일반 JSON 오류: `{ "success": false, "message": "..." }` (`global/exception/GlobalExceptionHandler.java`). 메시지는 한국어/영어가 혼재한다.
- SSE(`/api/v1/messages/stream`)와 첨부 다운로드는 별도 형식이다.

## 인증 방식

- Stateless JWT. 보호된 API는 `Authorization: Bearer <access token>`을 사용한다.
- Access token 만료 기본 1800초, refresh token 기본 1209600초(14일). Refresh token은 HttpOnly 쿠키(기본 이름 `refresh_token`)로 전달되며 `POST /api/v1/auth/refresh`로 갱신한다.
- 메시지 SSE는 `POST /api/v1/messages/stream-token`으로 발급한 전용 HttpOnly 쿠키로 인증한다.
- `/api/v1/admin/**`는 `ADMIN` 역할이 필요하다.
- `/api/**` 인증 실패는 JSON 401을 반환한다.
- 메시지 첨부는 `/uploads/message-attachments/**` 직접 접근이 차단되고, 인증 API(`/api/v1/messages/attachments/{id}`)로만 받을 수 있다.
- 관련 코드: `global/security/config/SecurityConfig.java`, `global/security/jwt/*`, `global/security/oauth2/OAuth2SuccessHandler.java`, `domain/auth/`.

## 실행 / 빌드 / 테스트

사전 준비: JDK 21, PostgreSQL, (미디어 기능 사용 시) FFmpeg. 모든 명령은 저장소 루트에서 실행한다.

```bash
./gradlew compileJava        # 컴파일 확인
./gradlew test               # 전체 테스트
./gradlew test --tests 'com.gamerin.backend.domain.message.service.MessageServiceTest'
./gradlew clean bootJar      # 배포 JAR 생성 (build/libs/)
./gradlew bootRun            # 로컬 실행
```

주의:

- `bootRun`은 실제 DB, 외부 API, 정리·정산 스케줄러에 영향을 줄 수 있으므로 대상 환경을 확인한 뒤 실행한다.
- 기본 활성 프로필은 `local`이라 `src/main/resources/application-local.yaml`이 필요하다. 이 파일은 비밀 설정이며 git에서 제외된다. 구조는 `application-local.example.yaml`을 참고해 직접 작성한다.
- 예제의 `ddl-auto: create`는 로컬 DB 스키마를 재생성할 수 있다. 사용 전 대상 DB를 확인한다.
- `BackendApplicationTests`(`@SpringBootTest`)의 실행 환경 요건은 `확인 필요`.
- 조건부 테스트: PostgreSQL 동시성 테스트는 `HASHTAG_POSTGRES_TEST_URL`, `BOOKMARK_POSTGRES_TEST_URL`, `NOTIFICATION_POSTGRES_TEST_URL`이 설정돼야 실행되고, `R6DataLiveIntegrationTest`는 `R6DATA_LIVE_TEST=true`일 때 실행된다. 조건이 맞지 않아 건너뛴 테스트는 통과로 보지 않는다.
- 테스트 JVM 설정(`build.gradle`): `-Xmx2048m`, `forkEvery = 30`.

## 설정 프로필과 환경변수

| 파일 (`src/main/resources/`) | 용도 |
| --- | --- |
| `application.yaml` | 공통 설정. 기본 프로필 `${SPRING_PROFILES_ACTIVE:local}`, 포트 8080, `ddl-auto: validate`, `open-in-view: false` |
| `application-local.yaml` | 로컬 비밀 설정 (git 제외, 예제: `application-local.example.yaml`) |
| `application-prod.yaml` | 운영 설정. Swagger/api-docs 비활성, `server.forward-headers-strategy: framework` |

### 운영 프로필 필수 환경변수 (기본값 없음)

`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `CORS_ALLOWED_ORIGINS`, `FRONTEND_BASE_URL`, `JWT_SECRET`

### 운영 프로필 선택 환경변수 (기본값 존재)

| 변수 | 기본값 |
| --- | --- |
| `DB_HOST` / `DB_PORT` | `postgres` / `5432` |
| `SPRING_MAIL_HOST` / `PORT` / `USERNAME` / `PASSWORD` | 빈 값 / `587` / 빈 값 / 빈 값 |
| `APP_MAIL_FROM_ADDRESS` | `no-reply@gamerin.local` |
| `FRONTEND_PASSWORD_RESET_PATH` | `/auth/reset-password` |
| `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS` / `JWT_REFRESH_TOKEN_EXPIRATION_SECONDS` | `1800` / `1209600` |
| `REFRESH_COOKIE_NAME` / `REFRESH_COOKIE_SECURE` / `REFRESH_COOKIE_SAME_SITE` | `refresh_token` / `true` / `Lax` |
| `PASSWORD_RESET_EXPIRATION_MINUTES` | `30` |
| `APP_MEDIA_UPLOAD_DIR` | `/app/uploads` |
| `APP_MEDIA_UPLOAD_MAX_CONCURRENCY` / `APP_MEDIA_UPLOAD_ACQUIRE_TIMEOUT_MS` | `1` / `5000` |
| `APP_MEDIA_VIDEO_MAX_FILE_SIZE_BYTES` / `APP_MEDIA_VIDEO_TEMP_STORAGE_RESERVE_BYTES` | `524288000` / `536870912` |
| `APP_MEDIA_VIDEO_OPTIMIZATION_ENABLED` / `_FFMPEG_PATH` / `_MAX_CONCURRENCY` / `_ACQUIRE_TIMEOUT_MS` / `_TIMEOUT_MS` | `true` / `ffmpeg` / `1` / `5000` / `60000` |
| `POST_CLEANUP_HARD_DELETE_AFTER` / `_INITIAL_DELAY_MS` / `_FIXED_DELAY_MS` | `24h` / `60000` / `3600000` |
| `OPENAI_API_KEY` | 빈 값 |
| `OPENAI_MODERATION_ENABLED` / `_MODEL` / `_FFMPEG_PATH` | `true` / `omni-moderation-latest` / `ffmpeg` |
| `PUBG_API_KEY`, `RIOT_API_KEY` | 빈 값 |
| `R6DATA_API_KEY` / `_BASE_URL` / `_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | 빈 값 / `https://api.r6data.com` / `3s` / `10s` |

공통 설정(`application.yaml`)에서 읽는 변수: `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE`(기본 `500MB`), `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE`(기본 `530MB`), `APP_HASHTAG_BACKFILL_ENABLED`(`false`), `APP_HASHTAG_BACKFILL_BATCH_SIZE`(`200`), `KAKAOPAY_SECRET_KEY`, `KAKAOPAY_CID`(비어 있으면 결제 준비 거부), `KAKAOPAY_CANCEL_ENABLED`(`false`), `OPENAI_MODERATION_FFMPEG_TIMEOUT_MS`(`15000`).

주의: `application-prod.yaml`에는 `kakaopay.*` 블록이 없고 공통 설정의 값을 상속한다. 운영 환경변수는 `../docker/docker-compose.yml`의 `backend.environment`와 대조해야 한다. `RIOT_API_KEY`가 Compose 주입 목록에 있는지, 실제로 주입되는지는 `확인 필요`.

## Docker 연동

- `Dockerfile`: 멀티 스테이지. 빌드는 `eclipse-temurin:21-jdk-jammy`에서 `./gradlew clean bootJar -x test`(테스트 제외), 런타임은 `eclipse-temurin:21-jre-jammy` + FFmpeg. 비root 사용자(uid 10001)로 실행, 8080 노출.
- 이미지 기본 환경: `SPRING_PROFILES_ACTIVE=prod`, `APP_MEDIA_UPLOAD_DIR=/app/uploads`, `TMPDIR=/app/tmp`, `JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m -Djava.io.tmpdir=/app/tmp"`.
- 이미지 빌드가 성공해도 테스트 통과를 뜻하지 않는다.
- 통합 구성은 별도 저장소(`../docker/`, 형제 배치 기준)의 Compose가 담당한다: 브라우저 → nginx → frontend:3000 / backend:8080 → PostgreSQL 16. `/api/`, `/oauth2/`, `/login/oauth2/`, `/uploads/`, Swagger 경로는 백엔드로 프록시된다. SSE 전용 buffering/timeout과 업로드 크기 제한은 `../docker/nginx/default.conf`에서 설정한다.
- 프론트의 `NEXT_PUBLIC_API_BASE_URL`은 빌드 타임 변수다.
- Compose 설정 검사: `docker compose --env-file .env config --quiet` (`docker/`에서 실행. 전체 구성은 출력하지 않는다.)

### 서버 배포 메모

- 중복 계정이 있는 상태에서 R6·Riot 중복 연동 방지 migration(V23)을 적용하면 기존 중복 연결은 자동으로 해제되지 않는다. 적용 전 중복 계정을 점검·정리해야 한다.


개발 서버의 기준 경로는 `~/capstone`을 사용한다.

```text
~/capstone/
  backend/
  frontend/
  docker/
    .env
    docker-compose.yml
    scripts/
  data/
    postgres/
    uploads/
    tmp/
```

백엔드 컨테이너는 내부에서 `/app/uploads`, `/app/tmp`를 사용한다. Docker Compose에서는 서버의 영속 경로를 아래처럼 연결한다.

```yaml
services:
  backend:
    volumes:
      - ../data/uploads:/app/uploads
      - ../data/tmp:/app/tmp
```

서버에서 업로드/임시 파일 디렉터리는 컨테이너 실행 유저인 `10001`이 쓸 수 있게 권한을 맞춘다.

```bash
sudo mkdir -p ~/capstone/data/uploads ~/capstone/data/tmp ~/capstone/data/postgres
sudo chown -R 10001:10001 ~/capstone/data/uploads ~/capstone/data/tmp
```

## DB 마이그레이션 규칙

- 위치: `src/main/resources/db/migration/V<버전>__<설명>.sql` (Flyway). 운영 프로필은 `flyway.enabled: true`, `baseline-on-migrate: true`.
- `ddl-auto`는 공통/운영 모두 `validate`다. 스키마 변경은 반드시 새 migration으로 한다.
- 이미 적용된 migration 파일을 수정하거나 버전 번호를 재사용하지 않는다.
- **새 migration은 홀수 버전만 사용**하며, 작업 브랜치와 병합 대상의 최대 버전보다 큰 미사용 홀수 번호를 고른다. 짝수 번호 공백은 정상이다.
- 현재 존재하는 버전(작성 시점): V1–V19, V21, V23, V24, V25, V27, V29, V35. 새 번호 선택 전 목록을 다시 확인한다.
- native SQL, 제약조건, 인덱스, locking 같은 PostgreSQL 동작은 H2 결과만으로 확정하지 않는다.

## Swagger

로컬에서는 springdoc 기본 설정을 따른다(`확인 필요`: 로컬 yaml 설정에 따라 다름). 운영 프로필은 `springdoc.api-docs.enabled`/`swagger-ui.enabled`가 `false`다.

## 기타

- 업로드: 게시물 미디어는 DB에 `/uploads/...` 상대경로를 저장한다. 정적 이미지는 JPEG로 재인코딩, 프로필 이미지는 JPEG/PNG만 허용, 동영상은 MP4/MOV/M4V 계열을 검증한다.
- 스케줄러: `PostCleanupService`(soft delete 게시물 hard delete), 결제 복구(`app.payment.recovery.*`, 기본 초기 지연 60000ms/주기 300000ms), `MentoringScheduler`(매일 03시 자동 정산).
- 기여 방법과 라이선스: `확인 필요`. 기존 `README.md`와 저장소 파일을 확인해야 한다.
