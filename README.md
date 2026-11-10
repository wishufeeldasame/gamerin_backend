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
| 외부 연동 | OpenAI Moderation, PUBG, R6Data, Riot, HenrikDev(발로란트), 주간 리포트 AI(Mindlogic 게이트웨이·NVIDIA Build), KakaoPay, SMTP 메일 |

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
| `/api/v1/users` | UserController | `GET/PATCH /me`, `PATCH /me/settings`, `POST /me/profile-images`, `GET /me/bookmarks`, `GET /{handle}`, `GET /{handle}/posts`, `GET /{handle}/media` |
| `/api/v1/users` | FollowController | `POST/DELETE /{handle}/follow`, `GET /{handle}/followers`, `GET /{handle}/following` |
| `/api/v1/users` | UserRecommendationController | `GET /me/recommendations?limit=5` (인증 필요, 1~20명) |
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

### 추천 친구 (#168)

`GET /api/v1/users/me/recommendations?limit=5`는 `ApiResponse<List<UserRecommendationResponse>>`를 반환한다.
각 항목은 `userId, handle, nickname, profileImageUrl, membershipBadge, mutualCount, representativeTier, winRate`다.
대표 티어는 기존 `{ game, tierLabel }` 계약이며 승률은 대표 게임의 마지막 저장 백분율(0~100)이다.
프로필 이미지·티어·승률이 없으면 각 필드는 null이다. 외부 게임 API를 호출하거나 계정 식별자를 공개하지 않는다.

- 활성 팔로잉 사용자가 팔로우하는 후보를 공통 연결 수 내림차순으로 추천한다. 동점은 최근 로그인(없으면 가입) 시각, UUID 내림차순이다.
- 부족분만 같은 최근 로그인/가입 순으로 보충한다(`mutualCount=0`). 자기 자신·기존 팔로우·정지·탈퇴 계정은 두 경로 모두 제외한다.
- 기본 5, 허용 1~20. 범위·형식 오류는 400, 비로그인은 401, 정지 회원은 403이다. 후보가 없으면 `data: []`다.
- 2단계 팔로우를 후보 ID별로 먼저 집계하고, 최대 20명의 프로필과 멤버십을 일괄 조회한다. 서비스 SQL은 결과가 있으면 3회(보충 시 4회)다.
- V65의 활성 사용자 최근 활동 부분 인덱스를 추가한다. 기존 팔로우 인덱스·유니크 키를 재사용한다. 새 테이블·캐시·스케줄러는 없다.
- 프론트 `feature/design-v2`의 `SuggestedFriendsCard` 목업(닉네임·대표 티어·승률·팔로우 버튼)에 맞춘 응답이다. 프론트 실제 연동은 별도 작업이며, 팔로우는 기존 `POST /api/v1/users/{handle}/follow`를 사용한다.

검증: `./gradlew test --tests '*UserRecommendation*'`. 동일 테스트를 PostgreSQL에서 실행하려면 **빈 전용 테스트 DB** `gamerin_recommendation_test`를 준비하고 `RECOMMENDATION_POSTGRES_TEST_URL`을 지정한다. 선택값은 `RECOMMENDATION_POSTGRES_TEST_USERNAME`(기본 postgres), `RECOMMENDATION_POSTGRES_TEST_PASSWORD`(기본 빈 값)다. 이 경우 Flyway 적용과 JPA validate도 확인한다. 운영 DB는 사용하지 않는다. H2/PG 전환 시 `--rerun-tasks`로 다시 실행한다.

## 인증 방식

- Stateless JWT. 보호된 API는 `Authorization: Bearer <access token>`을 사용한다.
- Access token 만료 기본 1800초, refresh token 기본 1209600초(14일). Refresh token은 HttpOnly 쿠키(기본 이름 `refresh_token`)로 전달되며 `POST /api/v1/auth/refresh`로 갱신한다.
- Refresh token은 갱신할 때마다 회전한다. `LocalAuthService.refresh`가 토큰 행을 비관적 잠금으로 조회하므로 같은 토큰의 동시 요청은 하나만 성공한다.
  - 회전된 토큰이 `app.auth.refresh-token.reuse-grace-seconds`(기본 30초) 안에 다시 오면 다른 탭의 동시 갱신으로 보고 `409`만 반환한다. `401`과 달리 refresh 쿠키를 지우지 않는다.
  - 유예 시간이 지난 뒤 다시 오거나 계정이 비활성이면 탈취로 보고 해당 사용자의 활성 refresh token을 모두 폐기한 뒤 `401`을 반환한다.
  - 로그아웃·비밀번호 변경·제재로 폐기된 토큰은 `401`만 반환한다(`refresh_tokens.rotated_at`으로 회전 폐기와 구분).
  - 발급 시 `refresh_tokens.ip_address`(`getRemoteAddr()`, 운영은 `forward-headers-strategy: framework` 반영)와 `user_agent`(최대 512자)를 기록한다.
- 메시지 SSE는 `POST /api/v1/messages/stream-token`으로 발급한 전용 HttpOnly 쿠키로 인증한다.
- `/api/v1/admin/**`는 `ADMIN` 역할이 필요하다.
- `/api/**` 인증 실패는 JSON 401을 반환한다.
- 메시지 첨부는 `/uploads/message-attachments/**` 직접 접근이 차단되고, 인증 API(`/api/v1/messages/attachments/{id}`)로만 받을 수 있다.
- 관련 코드: `global/security/config/SecurityConfig.java`, `global/security/jwt/*`, `global/security/oauth2/OAuth2SuccessHandler.java`, `domain/auth/`.

### 비로그인 공개 조회 (#189)

GET 전체 피드(`tab=all` 또는 생략), 게시물 상세·댓글, 타인 프로필·게시물·미디어, 멘토링 프로그램·멘토 소개·후기, 게임 코드를 공개한다. 팔로잉 피드·쓰기·개인 조회는 JSON 401로 보호한다. `/users/me`·하위 및 `/mentoring/mentors/me` 보호가 공개 동적 경로보다 우선한다.

익명 개인화 필드는 false, 편집 정보는 null이고 조회수는 세지 않는다. 공개 후기는 신청 ID를 제외한다. 프로그램·후기 페이지 크기는 최대 50이다. 삭제·관리자 숨김 프로그램과 미승인·비활성·정지·탈퇴 멘토는 공개하지 않는다. 일반 마감 프로그램은 상태와 함께 공개한다. OAuth 성공 콜백(`/auth/oauth-success`)·refresh·DB 스키마는 유지한다. Google 이메일 미인증·탈퇴 계정은 `/login` 대신 `/?login=1&error=email_not_verified` 또는 `/?login=1&error=account_withdrawn`으로 보내 중앙 모달에서 안내한다.

공개 조회 요청량 제한은 기존 정책을 유지한다. nginx의 일반 API 경로와 백엔드 공개 GET에는 새 익명 전용 rate limit을 추가하지 않았다.

프론트 #187의 공개 셸·중앙 로그인 모달 적용은 후속 작업이다. 로그인 완료만으로 보류된 쓰기를 자동 실행하지 않는다. 검색·해시태그·관계 목록과 결제 상품 공개는 이번 범위에서 제외한다.

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
- 조건부 테스트: PostgreSQL 동시성 테스트는 `HASHTAG_POSTGRES_TEST_URL`, `BOOKMARK_POSTGRES_TEST_URL`, `NOTIFICATION_POSTGRES_TEST_URL`, `REFRESH_TOKEN_POSTGRES_TEST_URL`이 설정돼야 실행되고, `R6DataLiveIntegrationTest`는 `R6DATA_LIVE_TEST=true`일 때 실행된다. 조건이 맞지 않아 건너뛴 테스트는 통과로 보지 않는다.
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
| `HENRIK_API_KEY` / `_BASE_URL` / `_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | 빈 값 / `https://api.henrikdev.xyz` / `3s` / `10s` |

주간 전적 리포트(`domain/weeklyreport`, 공통 설정에서 읽음): 매주 월요일 04:00 KST에 발로란트를 연동한 활성 멤버의 직전 주(KST 월~일) 경쟁전을 HenrikDev에서 수집해 `valorant_match_records`에 누적하고 `weekly_game_reports`를 만든다. 조회는 `GET /api/v1/memberships/weekly-report`(활성 멤버만), 관리자 수동 생성은 `POST /api/v1/admin/weekly-reports/valorant`다. 분석 문장은 OpenAI Chat Completions 호환 API로 만들고, 키·모델이 없거나 실패하면 규칙 기반 문장으로 대체한다.

리포트가 `READY`로 완성되면 현재 해당 계정을 연동한 활성 멤버십 회원에게 `weekly_report_ready` 시스템 알림을 생성한다. 자동 배치·관리자 수동 생성에 공통 적용하며, 같은 회원·리포트의 재생성은 기존 알림과 읽음 상태를 유지한다. `NO_DATA`·`FAILED`에는 완료 알림을 보내지 않는다. 리포트와 알림은 같은 트랜잭션에서 저장하며, 알림을 누르면 `/membership/content`로 이동한다. DB 변경은 `V73__add_weekly_report_ready_notifications.sql`을 적용한다.

배치는 전용 단일 스레드(`weekly-report-worker`)에서 실행하며 실행 중 중복 배치를 건너뛴다. 경기 목록 수집은 100경기 상한 없이 기간 경계·짧은 응답·404까지 페이지를 넘긴다. 짧은 응답만으로 전체 이력의 완전성을 보장하지는 않는다. 전체 페이지 반복이나 경기 ID·시각 누락은 502로 실패하며 목록의 부분 기록을 저장하지 않는다. 관리자 수동 생성은 동기 응답을 유지하고, `../docker/nginx/default.conf`에서 이 경로만 read/send timeout을 `1h`로 둔다. 클라이언트도 긴 응답 시간을 허용해야 하며, 1시간을 넘는 요청은 여전히 프록시에서 끊길 수 있다.

경기 목록이 성공했어도 특정 상세가 누락될 수 있다. 최근 최대 20건의 MMR 이력과 저장·수집된 경기 ID를 대조하고, 빠진 ID는 상세 API로 직접 확인한다. 상세를 받으면 저장하고, 404 등이 계속되면 가능한 경기의 통계·분석을 유지하면서 수집 진단을 기존 `stats` JSON의 `collectionIssues`에 보관한다(스키마 변경 없음). 사용자 GET 응답은 쉬운 `notice`만 제공하며 진단 목록은 관리자 생성 응답에만 포함한다. 전부 누락돼 `games: []`인 경우에도 안내를 유지한다. 경기 ID·날짜·진단은 AI 입력에서 제거한다. 기존 리포트에는 재생성 후 안내가 반영된다. 최근 이력 밖의 누락은 탐지하지 못할 수 있다.

| 환경변수 | 기본값 |
|---|---|
| `WEEKLY_REPORT_AI_PROVIDER` | `factchat` (학교 Mindlogic 게이트웨이) 또는 `nvidia` (NVIDIA Build) |
| `FACTCHAT_SECRET_KEY` / `FACTCHAT_MODEL` / `FACTCHAT_API_URL` | 빈 값 / 빈 값(예: `deepseek-v4.1-flash`) / `https://factchat-cloud.mindlogic.ai/v1/gateway/chat/completions/` |
| `NVIDIA_SECRET_KEY` / `NVIDIA_MODEL` / `NVIDIA_API_URL` | 빈 값 / 빈 값(예: `deepseek-ai/deepseek-v4.1-flash`) / `https://integrate.api.nvidia.com/v1/chat/completions` |
| `WEEKLY_REPORT_AI_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `3s` / `60s` (NVIDIA Build는 응답이 2분을 넘은 적이 있어 늘려야 한다) |
| `WEEKLY_REPORT_USER_INTERVAL` | `30s` (배치에서 사용자 사이 대기. HenrikDev Basic 키는 분당 30회) |

공통 설정(`application.yaml`)에서 읽는 변수: `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE`(기본 `500MB`), `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE`(기본 `530MB`), `APP_HASHTAG_BACKFILL_ENABLED`(`false`), `APP_HASHTAG_BACKFILL_BATCH_SIZE`(`200`), `KAKAOPAY_SECRET_KEY`, `KAKAOPAY_CID`(비어 있으면 결제 준비 거부), `KAKAOPAY_CANCEL_ENABLED`(`false`), `OPENAI_MODERATION_FFMPEG_TIMEOUT_MS`(`15000`). 환경변수 없이 공통 설정에 고정된 값: `app.auth.refresh-token.reuse-grace-seconds`(`30`).

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
- 현재 존재하는 버전(작성 시점): V1–V19, V21, V23–V25, V27, V29, V31, V33, V35, V37, V39–V43, V45, V47–V49, V51. 새 번호 선택 전 목록을 다시 확인한다.
- native SQL, 제약조건, 인덱스, locking 같은 PostgreSQL 동작은 H2 결과만으로 확정하지 않는다.

## Swagger

로컬에서는 springdoc 기본 설정을 따른다(`확인 필요`: 로컬 yaml 설정에 따라 다름). 운영 프로필은 `springdoc.api-docs.enabled`/`swagger-ui.enabled`가 `false`다.

## 기타

- 업로드: 게시물 미디어는 DB에 `/uploads/...` 상대경로를 저장한다. 정적 이미지는 JPEG로 재인코딩, 프로필 이미지는 JPEG/PNG만 허용, 동영상은 MP4/MOV/M4V 계열을 검증한다.
- 스케줄러: `PostCleanupService`(soft delete 게시물 hard delete), 결제 복구(`app.payment.recovery.*`, 기본 초기 지연 60000ms/주기 300000ms), `MentoringScheduler`(매일 03시 자동 정산).
- 기여 방법과 라이선스: `확인 필요`. 기존 `README.md`와 저장소 파일을 확인해야 한다.
