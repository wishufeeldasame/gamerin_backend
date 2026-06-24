# GamerIN Backend

**기획 및 테스트**
- 서장호

**프론트엔드**
- 전준범 (팀장)
- 김신의

**백엔드**
- 방경식
- 이상혁


## 백엔드 셋업

### 1. 환경 설정
PostgreSQL 설치 : https://www.enterprisedb.com/downloads/postgres-postgresql-downloads
설치 방법 참고 : https://nazzang19.tistory.com/30

JDK 21 설치 : https://download.oracle.com/java/21/latest/jdk-21_windows-x64_bin.exe
JDK 환경변수 참고 : https://mi2mic.tistory.com/231


### 2. DB 준비 (아직 미정)
SQL Shell(psql) 실행

<img width="547" height="362" alt="Image" src="https://github.com/user-attachments/assets/b6cea94c-efc6-493a-9162-1d2caaead513" />

gamerin DB 생성 


### 3. application-local.yaml 설정
**backend/src/main/resources/application-local.example.yaml** 을 참고하여 같은 디렉토리에 application-local.yaml 생성.
그 후 본인이 설정한 패스워드 입력

### 4. 서버 실행
./gradlew bootRun

## Docker 운영 배포 메모

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


### 5. 수정 일지

- **26/04/20** 서장호
  
  > 비밀번호 재설정 토큰 관리를 위한 PasswordResetToken 엔티티 및 레포지 추가  
  > 비밀번호 재설정 이메일 전송을 위한 PasswordResetMailService 구현  
  > Swagger 사용 및 인증 없이 접근 가능하도록 SecurityConfig 수정  
  > 메일 설정 및 비밀번호 재설정 경로를 포함하도록 애플리케이션 설정 파일 수정  
  > LocalAuthService에 토큰 생성 및 검증을 포함한 비밀번호 재설정 로직 구현  
  > 비밀번호 재설정 흐름에 대한 통합 테스트 및 LocalAuthService, TokenService 단위 테스트 추가  
  > password_reset_tokens 테이블 생성을 위한 데이터베이스 마이그레이션 스크립트 추가  
  > 기능 테스트 코드 추가  

- **26/04/23** 서장호

  > `POST /api/v1/auth/refresh`에서 리프레시 토큰이 유효하지 않아 `401 UNAUTHORIZED`가 나면, 예외를 다시 던지기 전에 refresh cookie를 즉시 삭제하도록 변경. 목적은 서버 재기동 뒤 남아 있던 오래된 refresh cookie가 계속 세션 복구를 시도하게 하지 않도록 막는 것.  
  > JWT 자체는 유효하지만 DB에 해당 사용자가 더 이상 없을 때 `UsernameNotFoundException`을 잡아 `SecurityContext`를 비우도록 변경. 즉, 예전에는 이런 경우 필터에서 비정상 흐름이 날 수 있었는데, 지금은 “미인증 사용자”로 안전하게 처리  
  > refresh 요청이 `401`일 때 `Set-Cookie`로 refresh cookie가 `Max-Age=0`으로 지워지는 테스트 코드 추가  
  > 정상 JWT + 정상 사용자면 인증이 세팅되는지, JWT는 유효하지만 사용자가 없으면 인증이 비워지는지를 검증하는 테스트 코드 추가됨 

- **26/05/20** 서장호

  > OpenAI Moderation API를 통한 게시물 이미지/텍스트/동영상 검열 추가  
  > 동영상은 시작 중간 끝 프레임 이미지 추출하여 API를 통해 검열함  
  > 검열 정책 1차 수정  
  > API 요청 단위 성공/실패 로그를 JSON 형식으로 콘솔에 출력하는 공통 로깅 필터 추가  
  > 기능 구분, 요청 경로, HTTP 상태 코드, 처리 시간, 사용자 ID, 실패 사유가 로그에 포함되도록 구현  
  > 기존 비밀번호 재설정 링크 출력 로그와 OpenAI Moderation 경고 로그를 JSON 형식 이벤트 로그로 변경  
  > 인증 실패 및 주요 요청 오류의 실패 사유가 JSON 로그에 기록되도록 예외 처리 로직 보강  
  > Hibernate SQL 원문 콘솔 출력을 막기 위해 `spring.jpa.show-sql` 설정 비활성화  

- **26/05/24** 서장호  

  > 동영상 저장 전 FFmpeg로 빠른 MP4 최적화(stream copy, faststart, metadata 제거)를 먼저 시도하고, 실패 시 1080p 품질 기준의 H.264 변환을 수행하도록 변경  
  > 최적화된 동영상 임시 파일을 저장할 수 있도록 MediaStorageService에 PreparedMediaPath 저장 흐름 추가  
  > 경량 보안 스캔 추가: 실행 파일 헤더, EICAR 테스트 시그니처 차단  
  > 텍스트 입력에 위험 마크업과 제어문자 차단 로직 추가  
  > 이미지/썸네일/동영상 모두 저장 전 경량 파일 스캔을 거치도록 업로드 흐름 보강  
  > application.yaml과 프론트 연동 문서에 동영상 최적화 및 업로드 보안 기준 반영  
  > PostService, 동영상 최적화, 텍스트 보안, 경량 파일 스캔 기능 테스트 코드 추가  

- **26/05/30** 서장호  

  > Docker 운영 배포를 위한 백엔드 Dockerfile과 .dockerignore 추가  
  > Java 21 빌드/런타임 이미지를 분리하고, 런타임 이미지에 FFmpeg를 설치하도록 구성  
  > 컨테이너 내부 업로드 경로를 `/app/uploads`, 임시 파일 경로를 `/app/tmp`로 설정하고 non-root 유저로 실행하도록 변경  
  > 운영용 `application-prod.yaml`을 추가하여 DB, JWT, OAuth, SMTP, CORS, OpenAI, PUBG 설정을 환경변수로 주입하도록 구성  
  > refresh cookie의 Secure, SameSite 설정을 하드코딩하지 않고 환경변수로 제어하도록 AuthController와 OAuth2SuccessHandler 수정  
  > reverse proxy 뒤에서 업로드 URL이 올바르게 생성되도록 `server.forward-headers-strategy` 설정 추가  
  > 메인 병합 후 중복된 Flyway V5 마이그레이션 문제를 해결하기 위해 프로필 컬럼 추가 마이그레이션을 V7로 변경  
  > Docker 관련 readme 추가  
  > nginx/reverse proxy 전환 후 IP, 포트, 도메인 변경에 영향을 받지 않도록 게시글 미디어 URL을 절대주소 대신 `/uploads/post-media/...` 상대경로로 저장하도록 변경  
  > 기존 DB에 저장된 `http://.../uploads/...` 형식의 게시글 미디어 URL을 `/uploads/...` 상대경로로 보정하는 V8 Flyway 마이그레이션 추가  

- **26/05/31** 서장호  

  > 게시글 상세 화면에서 기존 댓글 목록을 불러올 수 있도록 `GET /api/v1/posts/{postId}/comments` API 추가  
  > 댓글 목록 조회 시 삭제되지 않은 댓글만 최신순으로 반환하고, 작성자 정보와 프로필 정보를 함께 조회하도록 PostCommentRepository 쿼리 보강  
  > PostService에 댓글 목록 조회 로직 추가 및 CommentResponse 변환 흐름 연결  
  > 댓글 목록 조회 기능 테스트 코드 추가  
  > 작성자 본인 댓글을 즉시 hard delete 할 수 있도록 `DELETE /api/v1/posts/{postId}/comments/{commentId}` API 추가  
  > 댓글 삭제 시 게시글 댓글 수를 함께 감소시키고, 댓글 목록 응답에 본인 댓글 여부를 나타내는 `mine` 필드 추가  
  > 작성자 본인 게시물을 삭제할 수 있도록 `DELETE /api/v1/posts/{postId}` API 추가  
  > 게시물 삭제 요청 시 즉시 `deleted_at`을 채워 soft delete 처리하고, 피드/상세 조회에서는 보이지 않도록 기존 active 조회 흐름과 연결  
  > soft delete 후 24시간이 지난 게시물을 스케줄러가 hard delete 하도록 PostCleanupService 추가  
  > hard delete 시 `posts` row를 실제 삭제하여 댓글, 좋아요, 북마크, 공유, 미디어 DB row가 cascade로 정리되도록 구현  
  > hard delete 대상 게시물의 `media_url`, `thumbnail_url`을 실제 업로드 파일 경로로 변환하여 서버 파일도 함께 삭제하도록 MediaStorageService 보강  
  > 게시물 정리 주기 설정을 `app.post.cleanup.*`으로 추가하고 운영 환경에서는 환경변수로 조정할 수 있도록 구성  
  > 게시물 삭제 권한, soft delete, hard delete 스케줄러, 미디어 파일 삭제 경로 검증 테스트 코드 추가  

- **26/06/04** 서장호  

  > 멘토 미등록 사용자의 멘토링 화면 진입을 실패 요청으로 판별하지 않도록 `GET /api/v1/mentoring/mentors/me` API 추가  
  > `/mentors/me`는 현재 로그인 사용자가 멘토면 멘토 프로필을 반환하고, 멘토 등록 전이면 `data: null`을 정상 응답으로 반환하도록 구현  
  > 기존 `GET /api/v1/mentoring/mentors/{mentorId}`는 특정 멘토 공개 조회 API로 유지하고, 존재하지 않는 멘토는 `404 NOT_FOUND`로 반환하도록 변경  
  > `MentoringApplicationResponse`에 `mentorId`, `menteeId`, `reviewed` 필드를 추가하여 프론트가 채팅 상대와 리뷰 작성 여부를 서버 응답 기준으로 판단하도록 정리  
  > `MentoringReviewResponse`에 `programId`, `programTitle` 필드를 추가하여 리뷰 화면에서 프로그램 정보를 안정적으로 표시하도록 보강  
  > 신청 목록 조회 시 페이지 내 application id들을 기준으로 리뷰 작성 여부를 한 번에 조회하는 `findReviewedApplicationIds()` 쿼리 추가  
  > 단건 신청 응답은 `existsByApplicationId()`로, 목록 신청 응답은 배치 조회 결과로 `reviewed` 값을 채우도록 `MentoringService` 응답 변환 흐름 정리  
  > 멘토 미등록, 존재하지 않는 멘토 404, 신청 목록 응답의 참여자 ID 및 리뷰 여부를 검증하는 `MentoringServiceTest` 추가  

- **26/06/06** 서장호

  > 대화방을 나간 사용자가 단순 대화방 조회/생성 요청만으로 상대방에 의해 재활성화되지 않도록 DM participant 재활성화 흐름을 분리
  > 나간 사용자가 직접 같은 상대와 대화방을 다시 열 때는 본인 participant만 복구하고, 상대가 새 메시지를 보낼 때만 incoming message 기준으로 수신자 participant를 복구하도록 정리
  > 공유 게시글만 포함한 메시지가 게시글 hard delete 시 체크 제약을 깨지 않도록 `content`를 빈 문자열로 저장하도록 변경
  > 기존 `content is null and shared_post_id is not null` DM 데이터를 보정하는 `V13__normalize_direct_message_shared_post_content.sql` Flyway 마이그레이션 추가
  > 메시지 커서 페이징이 `createdAt`만 비교해 동일 timestamp 메시지를 누락하지 않도록 `(createdAt, id)` 튜플 기준으로 조회 조건 보강
  > 메시지 수정 API, `UpdateMessageRequest`, `editedAt` 응답 필드, `message-updated` 실시간 이벤트, 엔티티 edit 로직 제거
  > DM 삭제/대화방 나가기 명세와 변경 가이드를 현재 지원 기능 기준으로 정리
  > 재활성화 정책, 공유 게시글 전용 메시지 저장, 커서 id 전달을 검증하는 `MessageServiceTest` 추가 및 수정
  > 검증: `./gradlew test` 통과

  > 요약 : DM 대화방 재활성화 정책, 공유 게시글 메시지 저장, 커서 페이징을 보정하고 메시지 수정 기능을 제거

- **26/06/15** 서장호

  > 프로필의 `followersCount`, `followingCount`가 팔로워/팔로잉 목록 조회와 같은 기준을 사용하도록 삭제된 사용자를 제외하는 active count 쿼리로 변경
  > 팔로워/팔로잉 목록 native SQL에서 PostgreSQL/H2 UUID 반환 차이를 줄이기 위해 follow id를 문자열로 조회하고 서비스 내부에서 UUID로 변환하도록 보완
  > 팔로우/언팔로우 및 프로필 도메인의 사용자-facing 에러 메시지를 한국어 기준으로 정리
  > 삭제된 사용자를 제외한 active count, 팔로워/팔로잉 목록 native SQL의 삭제 사용자 필터, 커서 조건을 검증하는 `FollowRepositoryTest` 추가
  > repository 반환 타입 변경에 맞춰 `FollowServiceTest` mock 데이터 수정
  > Docker PostgreSQL에서 Flyway V14 적용과 follow list 인덱스 생성을 확인
  > 검증: `./gradlew test` 통과

  > 요약 : 팔로워/팔로잉 목록 기능의 카운트 기준, 메시지 언어, native SQL UUID 처리와 검증을 보강

- **26/06/15** 서장호

  > `POST /api/v1/users/me/profile-images` multipart 업로드 API 추가
  > 업로드 대상은 `target=PROFILE|COVER`로 구분하고, 응답으로 `/uploads/profile-images/{userId}/profile|cover/{uuid}.jpg` 상대경로와 저장 크기를 반환하도록 구현
  > 업로드 API 성공 시 `UserProfile.profileImageUrl` 또는 `coverImageUrl`을 즉시 갱신하여 업로드 후 PATCH 실패로 인한 orphan 파일 위험을 줄이도록 변경
  > 프로필 아바타 원본 업로드는 2MB 이하, 커버 원본 업로드는 5MB 이하로 제한
  > 기존 경량 파일 스캔과 이미지 magic header 검증, JPEG 재인코딩 흐름을 프로필 이미지 업로드에도 적용
  > 아바타는 최대 512px/700KB, 커버는 최대 1920px/2MB 기준으로 저장 이미지 크기를 줄이도록 보강
  > `PATCH /api/v1/users/me`에서 `profileImageUrl`, `coverImageUrl`은 현재 사용자 ID가 포함된 `/uploads/profile-images/{userId}/.../{uuid}.jpg` 경로만 허용하도록 검증 추가
  > 기존 프로필 이미지가 새 프로필 이미지로 교체되거나 비워질 때 트랜잭션 커밋 이후 현재 사용자와 target이 일치하는 `/uploads/profile-images/{userId}/profile|cover/` 하위 파일만 best-effort로 삭제하도록 정리
  > 게시물 영상 500MB와 썸네일/멀티파트 오버헤드를 고려해 Spring multipart 전체 요청 상한을 530MB로 조정
  > 검증: `./gradlew test` 통과

  > 요약 : 프로필 이미지/커버를 data URL이 아닌 서버 업로드 파일 URL 기준으로 저장할 수 있도록 업로드 API와 URL 검증을 추가

- **26/06/15** 서장호

  > 메시지 수신자 응답 DTO에 `profileImageUrl` 필드 추가
  > `MessageResponseAssembler`에서 수신자 사용자 프로필의 `profileImageUrl`을 읽어 메시지 수신자 응답에 포함하도록 변경
  > 프론트 메시지 화면이 대화 목록, 새 대화 검색, 대화 헤더, 상대방 말풍선에서 최신 프로필 사진을 표시할 수 있도록 API 계약 보강
  > 검증: `git diff --check`, `./gradlew compileJava`, `./gradlew test` 통과

  > 요약 : 메시지 API 응답에 수신자 프로필 이미지 URL을 포함해 프로필 사진 변경이 메시지 화면에도 반영되도록 정리

- **26/06/18** 서장호

  > production profile에서 `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`를 적용해 Swagger/OpenAPI 문서가 운영 환경에서 노출되지 않도록 변경
  > `SecurityConfig`에서 Swagger UI, OpenAPI docs 경로를 springdoc 활성화 설정에 따라 공개/차단하도록 분리
  > Swagger/OpenAPI 차단 경로와 일반 보호 경로가 Google OAuth 로그인으로 자동 리다이렉트되지 않도록 인증 실패 응답 흐름 보강
  > `/api/**` 인증 실패는 기존처럼 JSON `401` 응답을 유지하고, `/oauth2/authorization/google` 직접 접근은 Google OAuth 시작 경로로 유지
  > Swagger/OpenAPI 차단, OAuth 자동 리다이렉트 방지, API JSON 401 응답 계약을 검증하는 `SecurityConfig` 테스트 추가
  > 검증: `./gradlew test --tests 'com.gamerin.backend.global.security.config.*'`, `./gradlew test` 통과

  > 요약 : 운영 환경 Swagger/OpenAPI 공개를 차단하고, 보호 경로의 의도치 않은 Google OAuth 자동 진입을 방지

- **26/06/18** 서장호

  > DM 첨부 파일이 원본 `MultipartFile` 그대로 `/uploads/message-attachments/**`에 저장되지 않도록 변경
  > DM 텍스트에는 게시글/댓글과 동일하게 `TextSecurityService`와 `ContentModerationService` 검열 흐름 적용
  > DM 이미지 첨부는 게시글 이미지 업로드처럼 JPEG/PNG 검증, magic header 검사, 경량 파일 스캔, OpenAI moderation, JPEG 재인코딩 후 `.jpg`로 저장
  > DM 비디오 첨부는 게시글 비디오 업로드처럼 MP4 계열 검증, 경량 파일 스캔, 2분 duration 제한, frame moderation, FFmpeg 준비 후 서버 결정 확장자로 저장
  > `.html`, `.svg`, 조작된 `Content-Type: image/*` 파일이 공개 정적 파일로 저장되지 않도록 회귀 테스트 추가
  > `/uploads/message-attachments/**` 직접 정적 접근을 차단하고, DM 첨부는 `GET /api/v1/messages/attachments/{attachmentId}` 인증 API에서 대화 참여자만 내려받도록 변경
  > DM 메시지 삭제 시 연결된 첨부 파일을 즉시 best-effort 삭제해 기존 첨부 URL/API 접근이 남지 않도록 정리
  > 검증: `./gradlew test --tests 'com.gamerin.backend.domain.message.service.MessageServiceTest' --tests 'com.gamerin.backend.domain.post.service.MediaUploadSecurityServiceTest' --tests 'com.gamerin.backend.domain.post.moderation.ContentModerationServiceTest'`, `./gradlew test --tests 'com.gamerin.backend.domain.message.service.MessageServiceTest' --tests 'com.gamerin.backend.global.security.config.SecurityConfigTest' --tests 'com.gamerin.backend.global.security.config.SecurityConfigOAuthEntryPointIntegrationTest'` 통과

  > 요약 : DM 첨부 저장 전 보안 검사와 검열을 게시글/댓글 흐름에 맞춰 적용하고, DM 첨부 파일은 인증된 참여자만 접근할 수 있도록 정리

- **26/06/24** Codex

  > `fix/deployement_risks` 브랜치를 기준으로 두고 `origin/fix/messags`의 메시지 변경 중 필요한 부분만 선별 이식
  > `POST /api/v1/messages/stream-token` API와 `SseStreamTokenService`를 추가하여 메시지 SSE 연결용 짧은 수명 HttpOnly 쿠키를 발급하도록 변경
  > `/api/v1/messages/stream?accessToken=...` 쿼리 토큰 인증을 제거하고, SSE 스트림은 Bearer 토큰 또는 발급된 스트림 쿠키로만 인증하도록 `JwtAuthenticationFilter` 수정
  > 쿼리 문자열 토큰은 브라우저 히스토리, 프록시/서버 로그, referer 등에 남을 수 있어 SSE 연결 인증 전용 쿠키로 분리
  > 1:1 DM 대화방 및 참여자 생성은 `insert ... on conflict do nothing` 기반으로 변경하여 동시 요청 시 중복 생성/unique 충돌 위험을 줄임
  > DM 생성/삭제 실시간 이벤트 publish와 삭제 메시지의 첨부 파일 정리를 트랜잭션 commit 이후로 지연하여 롤백된 메시지가 전파되거나 커밋 전 파일이 삭제되는 문제를 방지
  > DM 첨부 다운로드 조회 쿼리에서 메시지/대화방 삭제 여부와 요청자의 활성 참여자 여부를 함께 확인하도록 강화
  > 공유된 게시글이 삭제된 경우 메시지 응답에 원본 내용을 노출하지 않고 `삭제된 게시글` placeholder를 내려주도록 변경
  > `origin/fix/messags`의 오래된 `SecurityConfig`, `MediaResourceConfig`, 첨부 저장 로직은 가져오지 않음. 이유는 현재 브랜치의 Rate Limit, 운영 Swagger 차단, OAuth URL 토큰 제거, 프로필 이미지/DM 첨부 보안 검증 흐름을 되돌릴 수 있기 때문
  > 검증: `./gradlew test --tests com.gamerin.backend.domain.message.service.MessageServiceTest --tests com.gamerin.backend.domain.message.controller.MessageControllerTest --tests com.gamerin.backend.global.security.jwt.JwtAuthenticationFilterTest`, `./gradlew test` 통과

  > 요약 : 배포 리스크 보안 변경을 유지하면서 메시지 SSE 인증, 대화방 생성 동시성, 실시간 이벤트/첨부 삭제 트랜잭션 정합성을 보강
