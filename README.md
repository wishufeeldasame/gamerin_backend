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
