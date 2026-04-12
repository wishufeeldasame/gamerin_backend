# GamerIN

**기획 및 테스트**
- 서장호

**프론트엔드**
- 전준범 (팀장)
- 김신의

**백엔드**
- 방경식
- 이상혁


## 프론트엔드 셋업

### 1. 다음 버전이 설치되어 있어야 함

- Node.js 22.22.1 LTS
- npm 10 이상

Node 버전 확인:
```bash
node -v
npm -v
```
### 2. 프로젝트 루트에서 아래 명령어 실행
```bash
cd frontend
npm install
```
설치된 패키지 버전 확인
```bash
cd frontend
npm list
```
### 3. 개발 서버 실행
```bash
cd frontend
npm run dev
```

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