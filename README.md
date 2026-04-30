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