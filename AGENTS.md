# AGENTS.md

이 문서는 GamerIN 백엔드 저장소 루트에서 Codex가 작업할 때 적용하는 팀 지침이다. 이 저장소만 클론하거나 열어도 사용할 수 있도록 공통 작업 규칙을 포함한다.

## 적용 범위와 저장소 경계

- 아래 파일 경로와 명령은 별도 설명이 없으면 **이 저장소 루트** 기준이다. 폴더 이름이 `backend`이거나 상위에 `capstone/`이 있을 필요는 없다.
- 상위 `capstone/`의 지침을 읽거나 가져오는 것을 전제로 하지 않는다.
- `../frontend/`, `../backend/`, `../docker/`는 세 저장소를 형제로 배치했을 때의 경로 예시다. 다른 배치에서는 실제 체크아웃 경로를 사용한다.
- 다른 저장소와 연결된 변경은 접근 가능한 해당 저장소의 코드와 `AGENTS.md`(있는 경우)를 확인한다. 저장소가 없으면 현재 저장소에서 가능한 작업을 진행하고, 교차 검증하지 못한 계약과 필요한 자료를 보고한다. 경로를 추측하거나 자동으로 클론하지 않는다.
- 구현 설명은 탐색을 위한 참고이며 실제 코드와 다르면 현재 코드를 기준으로 판단한다. 설계 의도·보안 정책·작업 안전 규칙은 임의로 완화하지 않는다.
- 더 하위의 구체적인 작업 지침을 따르되, 이를 안전 정책을 완화하는 근거로 삼지 않는다.

## 작업 원칙

- 모든 답변과 완료 보고는 한국어로 작성한다.
- 작업 전에 관련 파일과 기존 구현 흐름을 확인하고, 수정 전에 변경 범위와 검증 방법을 간단히 제시한다.
- 명시적인 개발·수정·버그 해결·리팩터링·설정 변경 요청은 해당 범위의 수정 승인으로 간주한다.
- 조사·설명·검토·제안 요청만으로 파일을 수정하지 않는다. 수정 요청인지 불분명하면 변경 전에 확인한다.
- 요청 범위에 필요한 최소 변경만 수행하고 관련 없는 리팩터링이나 포맷 변경을 섞지 않는다. 큰 변경은 단계별로 진행한다.
- 기존 코드 스타일, 패키지 구조, 네이밍, API/응답 형식을 우선 유지한다.
- 확인한 사실, 추정, 권장사항, 사용자 결정이 필요한 내용을 구분하고 미확인 사항은 `확인 필요`로 표시한다.

## 커스텀 서브에이전트 위임

- `.codex/agents/`의 커스텀 서브에이전트는 아래 작업 유형에 맞을 때 사용한다.
- 단순 조회나 한 파일의 작은 수정처럼 위임 비용이 더 큰 작업에는 서브에이전트를 만들지 않는다.
- 서로 독립적인 하위 작업만 병렬로 위임하고, 메인 에이전트는 결과를 검증·통합한 뒤 최종 판단을 내린다.
- 구현을 위임할 때는 담당 파일이나 모듈을 명시하고, 다른 작업자의 변경을 되돌리지 않도록 지시한다.

| 작업 유형 | 서브에이전트 |
| --- | --- |
| API 계약 설계·호환성 검토 | `api-designer` |
| 범위가 특정된 백엔드 구현·버그 수정 | `backend-developer` |
| 재현이 어렵거나 여러 경로에 걸친 원인 분석 | `debugger` |
| Dockerfile·이미지·컨테이너 런타임 | `docker-expert` |
| 프론트와 백엔드를 함께 바꾸는 단일 기능 | `fullstack-developer` |
| PostgreSQL 스키마·쿼리·락·마이그레이션 | `postgres-pro` |
| IAM·비밀정보·네트워크·플랫폼 보안 | `security-engineer` |
| Spring Boot 설정·트랜잭션·JPA·API 구현 | `spring-boot-engineer` |
| 자동화 테스트·회귀 테스트·테스트 기반 개선 | `test-automator` |

## Git과 작업 안전

- 이 디렉토리는 독립 Git 저장소다. 작업 시작 시 이 저장소 루트에서 `git status --short --branch`를 확인하고 기존 사용자 변경을 보존한다.
- Git 명령은 대상 저장소에서 실행한다. `capstone/`처럼 여러 저장소를 모아 둔 상위 디렉토리를 하나의 Git 저장소로 취급하지 않는다.
- commit, push, merge, rebase, reset, `checkout --`, 변경 폐기 및 대량 삭제는 사용자 승인 후 수행한다.
- 컨테이너 기동·중지·재시작(`docker compose --env-file .env up -d`, `stop`, `restart` 등)은 승인 없이 수행할 수 있다. 전후 상태를 `ps`로 확인하고, 변경한 컨테이너와 이유를 완료 보고에 명시한다.
- DB 초기화·복원, 볼륨·데이터 디렉토리 삭제(`down -v` 포함), 배포 스크립트 실행과 실제 배포 등 그 밖의 런타임 상태 변경은 사용자 승인 후 수행한다.
- DB 복원 전에는 대상 DB, 최신 백업과 복원 파일을 확인한다.

## 민감정보와 런타임 데이터

- `.env`, `.env.local`, `application-local.yaml` 및 기타 비밀 설정·키·토큰 파일은 **존재 여부만 확인**한다.
- 민감 파일은 내용을 읽거나 검색·출력·복사·수정·덮어쓰기하지 않는다. 저장소 전체 검색에서도 제외한다.
- 설정 구조는 `.env.example`, `application-local.example.yaml` 같은 공개 예제만 확인한다. 예제로 실제 비밀 설정을 자동 생성하거나 덮어쓰지 않는다.
- 키·토큰·개인정보는 로그, 문서, 응답에 남기지 않는다.
- `data/`, `backups/`, 업로드·DB·임시 파일 등 런타임 데이터는 위치와 관계없이 임의로 수정하지 않는다. 형제 배치에서는 `../data/`, `../backups/`도 해당한다.
- Docker 명령이 내부적으로 `.env`를 사용하는 것은 가능하지만 파일 내용이나 변수가 확장된 전체 구성을 출력하지 않는다. Compose 설정 검사는 해당 저장소에서 `docker compose --env-file .env config --quiet`를 사용한다.

## 문서 작업과 지침 유지

- `docs/` 문서는 사용자가 요청한 경우에만 참고하거나 갱신한다.
- 문서의 경로·명령·구현 설명은 현재 코드를 근거로 작성한다.
- 기능 변경 후 README, API 명세, DB 문서, Docker 운영 문서의 갱신 필요 여부를 확인하고 보고한다.
- 이 저장소의 `AGENTS.md`와 `CLAUDE.md`는 각각 Codex와 Claude Code용이다. 팀 규칙을 변경할 때는 두 파일을 함께 갱신하고 내용이 어긋나지 않도록 확인한다.

## 기술 구성과 탐색 순서

> 이 절은 현재 구현 기준이다. 작업 시 관련 파일을 다시 확인한다.

- `build.gradle`: Spring Boot 3.5.12, Java 21 toolchain, Spring MVC/Security/Data JPA, OAuth2 Client, Validation, Mail, Flyway, PostgreSQL, JUnit Platform을 사용한다.
- Gradle wrapper(`./gradlew`)를 사용한다.
- `src/main/resources/application.yaml`: 공통 설정. 기본 활성 프로필은 `local`, 서버 포트는 8080이다.
- `src/main/resources/application-prod.yaml`: 운영 프로필 설정. 환경변수, 기본값, 고정 설정이 혼재한다.
- 운영 환경변수 주입은 `../docker/docker-compose.yml`과 대조한다.
- `RIOT_API_KEY`는 운영 프로필이 참조하지만 현재 Compose의 명시적 주입 목록에는 없으므로 컨테이너 연동 시 확인이 필요하다.
- 운영 비밀 값은 출력하지 않는다.
- 소스 루트는 `src/main/java/com/gamerin/backend/`, 테스트 루트는 `src/test/java/com/gamerin/backend/`다. 아래 `domain/...`, `global/...` 경로는 소스 루트 기준이다.
- `domain/<도메인>/` 아래 controller, service, repository, entity, dto 등 기존 계층을 따라 변경 흐름을 확인한다.
- 현재 도메인은 auth, user, post, message, mentoring, follow, bookmark, repost, hashtag, mention, notification, search, game, pubg, r6, riot로 나뉜다.
- 피드 controller/service는 `domain/post/`, 마일리지 controller/service는 `domain/user/`에 있다.
- `global/response`, `global/exception`, `global/security`, `global/config`, `global/logging`은 공통 기반이다.
- 기능을 바꾸기 전에 관련 controller → service → repository와 DTO/entity, 기존 테스트를 함께 확인한다.

## API와 인증

### 유지해야 하는 계약

- JSON 성공 응답은 `global/response/ApiResponse.java`의 `{ success: true, data: ... }` 형식을 유지한다.
- 커서 목록은 `global/response/CursorPageResponse.java`의 `{ items, nextCursor, hasNext }`를 `ApiResponse.data` 내부에서 사용한다.
- 기존 정렬·커서 비교 방식과 마지막 페이지 동작을 보존한다. 일부 구현은 `(createdAt, id)` 튜플 비교를 사용하므로 실제 코드를 확인한다.
- 오류 처리는 `global/exception/GlobalExceptionHandler.java`와 기존 `ResponseStatusException` 사용 패턴을 따른다.
- 일반 JSON 오류의 `{ success: false, message }` 계약을 유지한다. 기존 한국어·영어 메시지가 혼재한다.
- SSE와 첨부 파일 다운로드처럼 별도 응답 형식이 필요한 엔드포인트는 해당 구현을 따른다.
- API controller는 `/api/v1/...` 경로를 사용하지만 도메인과 경로가 일대일로 대응하지 않으므로 `/feed`, `/users`, `/bookmark-collections` 등 실제 매핑을 확인한다.
- API 변경 시 실제 controller 매핑과 요청/응답 DTO를 확인하고, 접근 가능한 프론트의 호출부와 응답 해석을 대조한다. 형제 배치의 호출부 경로는 `../frontend/src/lib/`이며, 프론트가 없을 때는 위 저장소 경계 규칙을 따른다.

### 현재 인증/보안 구현

- 인증·인가 변경은 `global/security/config/SecurityConfig.java`, `global/security/jwt/JwtAuthenticationFilter.java`, `global/security/jwt/JwtTokenProvider.java`, `global/security/oauth2/OAuth2SuccessHandler.java`, `domain/auth/controller/AuthController.java`, `domain/auth/service/TokenService.java`를 함께 확인한다.
- 보안 체인은 stateless JWT 기반이다.
- `JwtAuthenticationFilter`는 일반 Bearer 토큰과 메시지 SSE 스트림 전용 HttpOnly cookie 인증 경계를 구분한다.
- 메시지 SSE는 `POST /api/v1/messages/stream-token`과 `global/security/jwt/SseStreamTokenService.java`의 전용 쿠키 인증을 사용한다.
- 일반 Bearer 인증과 스트림 전용 인증의 적용 경계를 유지한다.
- `SecurityConfig`는 공개 패턴과 활성화된 Swagger/api-docs 경로를 허용하고, 비공개 첨부 및 비활성 Swagger 경로는 차단하며 나머지는 인증을 요구한다.
- `/api/**` 인증 실패는 JSON 401 응답을 반환하며 Google OAuth 자동 리다이렉트로 처리하지 않는다.
- Swagger/api-docs는 `springdoc.*.enabled` 설정에 따라 달라지며 운영 프로필의 기본 설정은 비활성이다.
- 현재 필터 체인에는 `RateLimitFilter`, `PostUploadConcurrencyFilter`, `PrivateUploadStaticPathDenyFilter`, `ApiRequestLoggingFilter` 등이 연결되어 있으므로 보안/업로드 변경 시 순서를 확인한다.
- `PrivateUploadStaticPathDenyFilter`는 `/uploads/message-attachments/**` 직접 정적 접근을 차단한다. 메시지 첨부는 인증 API로 다운로드한다.
- CORS, refresh cookie의 `Secure`/`SameSite`, `server.forward-headers-strategy`, 프록시 헤더, 필터 순서를 바꾸면 프론트 세션 갱신과 nginx 경로 영향도 함께 확인한다.

## DB와 트랜잭션

### 유지해야 하는 규칙

- 공통 및 운영 설정의 `ddl-auto`는 `validate`, `open-in-view`는 `false`다.
- lazy loading은 서비스 트랜잭션 경계 안에서 처리하고 N+1, cascade, 삭제 연관관계를 확인한다.
- 스키마 변경은 `src/main/resources/db/migration/`에 새 `V*.sql` Flyway migration을 추가한다.
- 이미 적용된 migration을 수정하거나 버전 번호를 재사용하지 않는다.
- 새 migration 추가 전 현재 파일 목록, 가장 높은 버전, 번호 누락을 다시 확인한다.
- native SQL, 제약조건, 인덱스, locking, 동시성 변경은 PostgreSQL 동작을 확인한다.
- H2 결과만으로 PostgreSQL 호환성을 확정하지 않는다.
- notification, mention 등 연관 쓰기가 포함된 기능은 트랜잭션 롤백, 중복 방지, 알림 생성 시점까지 기존 테스트와 대조한다.

### 현재 로컬 설정 참고

- 공개 로컬 예제 `application-local.example.yaml`은 `ddl-auto: create`를 사용한다.
- 실제 `application-local.yaml`은 비밀 설정이므로 존재 여부만 확인하고 읽거나 검색하거나 출력하거나 수정하지 않는다.
- 실제 로컬 설정을 확인하지 않으므로 실행 대상 DB와 데이터 삭제 가능성은 별도 확인이 필요하다.
- example 파일로 실제 로컬 설정을 자동 생성하거나 덮어쓰지 않는다.

## 업로드와 외부 연동

> 이 절은 현재 구현 기준이다. 관련 변경 시 실제 service/client와 설정을 다시 확인한다.

- 업로드 변경은 `domain/post/service/`의 미디어 저장·검증·FFmpeg 처리와 `domain/post/moderation/`, `domain/post/filter/`를 함께 확인한다.
- 메시지 첨부는 `domain/message/service/MessageAttachmentStorageService.java`도 확인한다.
- 업로드 파일은 서버 로컬의 `app.media.upload-dir`(컨테이너 `/app/uploads`)에 저장한다. 게시물 미디어는 DB에 `/uploads/...` 상대경로를 저장한다.
- 메시지 첨부는 `MessageAttachmentStorageService.publicAttachmentUrl()`에서 절대 URL을 만들어 저장하지만, `domain/message/service/MessageResponseAssembler.java`가 응답을 `/api/v1/messages/attachments/{id}` 인증 다운로드 경로로 변환한다. 게시물 미디어와 같은 공개 URL 정책으로 취급하지 않는다.
- 이미지의 magic header, 실행파일 헤더/EICAR 등 기존 보안 검사를 임의로 완화하지 않는다.
- 파일 크기, 형식 검사, 임시 저장 공간, 동시 처리 제한, timeout, 실패 시 임시 파일 정리와 검열 정책을 확인한다.
- 정적 이미지는 검증 후 JPEG로 재인코딩하며 GIF는 `AnimatedGifProcessor`로 별도 처리한다.
- 프로필 이미지는 JPEG/PNG만 허용한다.
- 동영상은 MP4/MOV/M4V 계열을 검증한다.
- `VideoOptimizationService`는 stream copy를 시도한 뒤 FFmpeg 명령 실패 시 H.264 변환으로 전환한다. 타임아웃 등은 오류 처리한다.
- 프레임 추출 검열과 실패 정책도 함께 확인한다.
- 텍스트·이미지·동영상 검열은 `ContentModerationService`와 OpenAI Moderation 연동을 확인하며 활성화 설정·실패 정책을 임의로 완화하지 않는다.
- `PostCleanupService`는 설정된 보존 기간이 지난 soft delete 게시물과 실제 파일을 삭제한다. 보존 기간 기본값은 24h이며 기간과 실행 주기는 `app.post.cleanup.*`로 설정한다.
- 연관 데이터 삭제는 entity와 DB 제약조건도 확인한다.
- `MentoringScheduler`는 서버 기본 시간대 기준 매일 03시에 자동 정산을 호출한다.
- `bootRun` 등 애플리케이션 실행을 단순 컴파일 확인으로 사용하지 않는다.
- OpenAI Moderation, PUBG, R6, Riot, 메일 연동은 관련 client/service와 공개 설정을 확인한다.
- API 오류, timeout, rate limit, 검열 실패 처리 정책을 임의로 완화하지 않는다.
- 키·토큰·개인정보는 로그와 문서에 남기지 않는다.
- OpenAI 검열 클라이언트는 `domain/post/moderation/OpenAiModerationClient.java`, 게임 API 클라이언트는 `domain/pubg/client/`, `domain/r6/client/`, `domain/riot/client/`에 있다.
- PUBG/R6 요약은 `game`, `connected`, `playerName`, `tierLabel`, `kd`, `winRate`, `matches`, `statsMode`를 공유한다.
- R6에는 `platform`, `updatedAt`이 추가된다.
- `GameStatsMode`는 `RANKED`, `NORMAL`이다.
- Riot 요약은 `gameName`, `tierLabel`, `kda`, `winRate`, `games`, `connected`로 별도 계약이므로 게임별 DTO와 프론트 매핑을 대조한다.

## 설정 프로필과 Docker 연동

아래 설정 파일은 `src/main/resources/` 기준이다.

- `application.yaml`: 공통 설정
- `application-local.yaml`: 로컬 비밀 설정, git 제외
- `application-prod.yaml`: 운영 설정
- 운영 설정은 환경변수, 기본값, 고정 설정이 혼재한다.
- 주입되는 변수는 `../docker/docker-compose.yml`의 `backend.environment`와 함께 확인한다.
- 실제 비밀 값은 읽거나 출력하지 않는다.
- 통합 구성은 브라우저 → nginx → frontend:3000 / backend:8080 흐름이며 PostgreSQL 16을 사용한다. API·OAuth·uploads는 백엔드로 프록시된다.
- Docker 저장소의 `nginx/default.conf`에서 SSE 전용 buffering/timeout, 업로드 경로의 크기 제한·rate limit과 프록시 헤더를 함께 확인한다.
- Compose의 업로드·임시·DB 볼륨과 포트 공개 범위는 실제 설정을 확인한다. 로컬 설정이 운영에서도 안전하다고 가정하지 않는다.
- 프론트의 `NEXT_PUBLIC_API_BASE_URL`은 빌드 타임 변수다. API 주소를 바꾸면 프론트 Dockerfile의 build arg, Compose 및 nginx 상대경로 호출과 로컬 직접 호출을 함께 확인한다.
- 배포 스크립트는 이미지 빌드뿐 아니라 서비스 기동과 nginx reload를 수행하므로 단순 검증 용도로 실행하지 않는다.

## 검증 명령과 실행 조건

명령 실행 전 파일 생성, DB 연결, 외부 API 호출 여부를 확인한다. 다음 명령은 이 저장소 루트에서 실행한다.

```bash
./gradlew compileJava
./gradlew test
./gradlew test --tests 'com.gamerin.backend.domain.message.service.MessageServiceTest'
./gradlew test --tests 'com.gamerin.backend.global.security.config.*'
./gradlew clean bootJar
```

- 기능·버그·리팩터링 변경 후 가능한 경우 관련 테스트와 `./gradlew test`를 실행한다.
- 컴파일만 빠르게 확인할 때는 `./gradlew compileJava`를 사용한다.
- 배포 산출물 확인이 필요할 때만 `./gradlew clean bootJar`를 실행한다.
- `Dockerfile`은 이미지 빌드에서 `bootJar -x test`로 테스트를 제외한다. 이미지 빌드 성공을 테스트 통과로 보고하지 않는다.
- JUnit/Mockito 단위 테스트, JPA 테스트, Spring 통합 테스트가 혼재한다.
- H2 테스트 의존성이 있지만 전체 테스트가 로컬 설정이나 외부 DB와 무관하다고 가정하지 않는다.
- `BackendApplicationTests`는 기본 `@SpringBootTest`이므로 프로필·DB 등 실행 환경은 `확인 필요`다.
- PostgreSQL 동시성 테스트는 `HASHTAG_POSTGRES_TEST_URL`, `BOOKMARK_POSTGRES_TEST_URL`, `NOTIFICATION_POSTGRES_TEST_URL` 조건으로 활성화된다.
- 각 테스트의 username/password 환경변수와 스키마 생성·정리 동작을 확인하고 테스트 전용 DB를 사용한다.
- 조건 미충족으로 건너뛴 테스트를 통과로 보고하지 않는다.
- `R6DataLiveIntegrationTest`는 `R6DATA_LIVE_TEST=true`일 때 활성화된다. 외부 API 호출 및 필요한 설정을 확인한 뒤 실행한다.
- `./gradlew bootRun`은 DB, 외부 연동, 정리 스케줄러에 영향을 줄 수 있으므로 실행 환경을 확인한 경우에만 사용한다.
- 비밀 설정이 없으면 자동 작성하지 않고 사용자에게 필요한 설정을 안내한다.
- 문서만 변경한 경우 참조 경로와 현재 코드의 일치 여부, `git diff --check`를 확인한다.
- 새 파일은 일반 diff에 포함되지 않을 수 있으므로 새 파일 내용도 별도로 점검한다.

## 완료 보고

- 변경 파일과 이유, 실행한 검증 명령과 결과를 보고한다.
- 실패하거나 생략한 검증은 이유와 필요한 환경을 구분해 보고한다.
- 남은 TODO 및 README/API/DB/Docker 문서 갱신 필요 여부를 설명한다.
- Notion 쓰기는 먼저 초안을 제시하고 대상 페이지를 확인한다.
- 여러 저장소를 수정했다면 저장소별 변경과 검증 결과를 나누어 보고한다.
