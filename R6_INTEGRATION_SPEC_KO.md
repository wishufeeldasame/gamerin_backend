# GamerIN R6 전적 연동 명세

## 1. 문서 목적

이 문서는 GamerIN 백엔드의 Rainbow Six Siege 전적 연동 정책과 구현 구조를 설명한다.

현재 전적 공급자는 Tracker Network가 아니라 [R6Data API](https://r6data.com/api-docs)다. `r6-data.js`는 Node.js용 SDK이므로 Spring Boot 백엔드에 설치하지 않고, [r6-data.js 문서](https://r6data.com/r6-data-js)는 API 계약을 확인하는 참고 자료로만 사용한다.

| 항목 | 현재 정책 |
| --- | --- |
| 외부 공급자 | R6Data |
| 대상 플랫폼 | PC |
| R6Data 플랫폼 타입 | `uplay` |
| 조회 모드 | 현재 시즌 경쟁전 우선, 기록이 없으면 통합 일반전 fallback |
| 인증 방식 | 서버가 `api-key` 헤더로 인증 |
| 프론트 API 계약 | 기존 계약 유지 |
| DB 스키마 변경 | 없음 |
| 티어 이미지 | 현재 응답 범위에서 제외 |

## 2. 핵심 정책

- 기존 R6 연결, 조회, 해제 API 주소와 응답 구조를 유지한다.
- R6 외부 연동 구현은 `R6StatsClient` 인터페이스 뒤에 격리한다.
- PUBG, 게시글, 북마크, 팔로우, 메시지 및 인증 정책을 변경하지 않는다.
- PC Ubisoft Connect 계정만 지원한다.
- 현재 시즌의 `pvp_ranked` 데이터가 1경기 이상이면 경쟁전 통계를 우선 사용한다.
- 현재 시즌 경쟁전 기록이 없으면 통합 일반전 `pvp_quickplay` 통계로 fallback한다.
- 일반전 fallback에서는 `tierLabel`만 `null`이고 `kd`, `winRate`, `matches`는 일반전 기준으로 제공한다.
- 경쟁전과 일반전 기록이 모두 없으면 `tierLabel`, `kd`, `winRate`, `matches`를 `null`로 처리한다.
- 외부 API 호출이 실패하면 기존에 저장된 R6 전적을 덮어쓰지 않는다.
- 내부 식별자인 `accountId`는 사용자 프로필 응답에 노출하지 않는다.
- 기존 `trackerProfileId`는 더 이상 신규 저장하지 않는다.
- 레거시 `trackerProfileId`는 프로필 응답에서 계속 차단하고, R6 재연결 또는 전적 갱신 시 제거한다.

## 3. 코드 구조

```text
domain/r6/
  client/
    R6StatsClient.java
    R6DataStatsClient.java
  controller/
    R6Controller.java
  dto/
    request/R6ConnectRequest.java
    response/R6ConnectionResponse.java
    response/R6SummaryResponse.java
  model/
    R6Profile.java
    R6ProfileRef.java
    R6SummaryStats.java
  service/
    R6Service.java
```

주요 책임은 다음과 같다.

| 구성 요소 | 책임 |
| --- | --- |
| `R6Controller` | GamerIN HTTP 요청과 응답 처리 |
| `R6Service` | 인증 사용자 확인, 연결 상태 관리, 전적 저장 및 해제 |
| `R6StatsClient` | 외부 R6 전적 공급자 추상화 |
| `R6DataStatsClient` | R6Data 요청, 응답 파싱, 외부 오류 변환 |
| `UserProfile` | `gameStats.R6` JSON 데이터 저장 |
| `UserService` | 공개 프로필에서 R6 내부 식별자 제거 |

의존 흐름은 다음과 같다.

```text
R6Controller
  -> R6Service
    -> R6StatsClient
      -> R6DataStatsClient
        -> https://api.r6data.com
```

## 4. R6Data 외부 API 계약

### 공통 설정

```text
Base URL: https://api.r6data.com
Header: api-key: {R6Data API key}
```

API 키는 [R6Data 대시보드](https://r6data.com/dashboard)에서 발급한다.

### 전체 모드 전적

```http
GET /api/stats?type=fullStats&nameOnPlatform={playerName}&platformType=uplay&modes=all
api-key: {R6Data API key}
```

이 응답에서 다음 정보를 사용한다.

- `data.platformInfo.platformUserId`
- `data.platformInfo.platformUserHandle`
- `data.metadata.currentSeason`
- `data.segments`
- `platform_families_full_profiles`

### 현재 티어

경쟁전 통계를 선택했지만 `fullStats` 응답에서 티어 이름을 확인할 수 없는 경우에만 다음 API를 추가 호출한다. 일반전 fallback에는 티어가 없으므로 호출하지 않는다.

```http
GET /api/stats?type=seasonalStats&nameOnPlatform={playerName}&platformType=uplay
api-key: {R6Data API key}
```

`data.history.data`에서 타임스탬프가 가장 최신인 항목의 `metadata.rank`를 현재 티어로 사용한다.

### 호출 횟수

| 상황 | 외부 호출 횟수 |
| --- | --- |
| 경쟁전 없이 일반전 기록만 있는 계정 | `fullStats` 1회 |
| 경쟁전과 일반전 기록이 모두 없는 계정 | `fullStats` 1회 |
| 랭크 계정이며 `fullStats`에 티어 이름 존재 | `fullStats` 1회 |
| 랭크 계정이며 티어 이름 추가 조회 필요 | `fullStats` + `seasonalStats`, 총 2회 |

`GET /api/v1/r6/me`도 저장된 값을 그대로 반환하지 않고 외부 전적을 갱신한다. 프론트에서 짧은 주기로 반복 호출하면 R6Data 사용량이 증가하므로 폴링 용도로 사용하지 않는다.

## 5. 전적 데이터 선택 정책

### 현재 시즌 판정

우선순위는 다음과 같다.

1. `data.metadata.currentSeason`
2. 최상위 `seasonNumber`
3. 위 값이 없으면 경쟁전 세그먼트 중 가장 큰 시즌 번호

### 경쟁전 세그먼트 조건

다음 조건을 만족하는 세그먼트만 사용한다.

- `attributes.gamemode == "pvp_ranked"`
- `attributes.platform`이 없거나 `pc`
- `attributes.sessionType`이 없거나 `ranked`
- `attributes.season`이 현재 시즌과 동일

동일 시즌의 경쟁전 세그먼트가 여러 개면 `rankPoints.value`가 가장 높은 항목을 사용한다.

경쟁전 `matchesPlayed`가 1 이상일 때만 경쟁전 기록이 있는 것으로 판단한다. 값이 없으면 랭크 보드의 승리, 패배, 이탈 수로 경기 수를 보정한 뒤 다시 판단한다.

### 일반전 fallback 조건

현재 시즌 경쟁전 경기 수가 없거나 0일 때만 일반전으로 fallback한다.

일반전 선택 우선순위는 다음과 같다.

1. `pvp_quickplay` 통합 일반전 세그먼트
2. 통합 세그먼트가 없으면 `pvp_standard`, `pvp_unranked`, `pvp_casual` 중 경기 수가 가장 큰 세그먼트

`pvp_quickplay`는 세부 Standard와 Quick Match 기록을 포함하는 통합값이므로 세부 세그먼트와 합산하지 않는다. 이렇게 해야 동일 경기를 두 번 세는 문제를 방지할 수 있다.

일반전 세그먼트도 `attributes.platform`이 없거나 `pc`인 데이터만 사용한다. 일반전 fallback에는 경쟁전 티어를 붙이지 않는다.

일반전 fallback 예시는 다음과 같다.

```json
{
  "tierLabel": null,
  "kd": 1.41,
  "winRate": 50.0,
  "matches": 50
}
```

위 예시에서 `matches`는 `pvp_quickplay` 통합값이다. 하위 `pvp_standard`와 `pvp_casual` 경기 수를 다시 더하지 않는다.

### 통계 매핑

| GamerIN 필드 | R6Data 원본 | 정책 |
| --- | --- | --- |
| `accountId` | `data.platformInfo.platformUserId` | 내부 저장 전용 |
| `playerName` | 연결 요청의 닉네임 | 앞뒤 공백 제거 후 저장 |
| `tierLabel` | 최신 seasonal history의 `metadata.rank` | 경쟁전 기록이 없으면 `null` |
| `kd` | 선택된 경쟁전 또는 일반전의 `stats.kdRatio.value` | K/D이며 소수점 둘째 자리까지 반올림 없이 절삭 |
| `matches` | 선택된 세그먼트의 `stats.matchesPlayed.value` | 없으면 승·패·이탈 경기 합계 사용 |
| `winRate` | 선택된 세그먼트 또는 랭크 보드의 승리·패배 수 | `wins / (wins + losses) * 100` 계산 후 정수 단위 반올림 |

승률 분모에는 `abandon`을 포함하지 않는다. 승리와 패배의 합이 0이면 승률은 `null`이다.

숫자 표시 정책은 PUBG와 동일하게 적용한다. R6는 어시스트를 포함한 KDA가 아니라 K/D를 사용하며, K/D는 `RoundingMode.DOWN`으로 소수점 둘째 자리까지 절삭하고 승률은 `Math.round`로 가장 가까운 정수에 맞춘다.

`matchesPlayed`가 없을 때만 다음 값을 사용한다.

```text
matches = wins + losses + abandon
```

현재 시즌 랭크 보드가 존재하더라도 경기 수가 0이면 랭크 통계로 사용하지 않고 일반전 fallback을 시도한다.

## 6. GamerIN R6 API

모든 R6 API는 로그인이 필요하다.

```http
Authorization: Bearer {accessToken}
```

### R6 계정 연결

```http
POST /api/v1/r6/connect
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "playerName": "R6Player"
}
```

검증 규칙:

- 빈 문자열 불가
- 앞뒤 공백 제거
- 최대 100자
- R6Data에서 `platformUserId`를 확인할 수 있어야 함

성공 응답:

```json
{
  "success": true,
  "data": {
    "connected": true,
    "playerName": "R6Player",
    "platform": "PC"
  }
}
```

연결 시점에 현재 전적도 함께 조회하고 저장한다.

### 내 R6 전적 조회 및 갱신

```http
GET /api/v1/r6/me
Authorization: Bearer {accessToken}
```

연결된 사용자 응답 예시:

```json
{
  "success": true,
  "data": {
    "game": "R6",
    "connected": true,
    "playerName": "R6Player",
    "platform": "PC",
    "tierLabel": "EMERALD II",
    "kd": 1.05,
    "winRate": 56.0,
    "matches": 117,
    "updatedAt": "2026-07-17T03:30:00+09:00"
  }
}
```

이 API는 호출할 때마다 R6Data에서 최신 전적을 조회하고 성공한 경우에만 저장값과 `updatedAt`을 갱신한다.

연결되지 않은 사용자 응답 예시:

```json
{
  "success": true,
  "data": {
    "game": "R6",
    "connected": false,
    "playerName": null,
    "platform": "PC",
    "tierLabel": null,
    "kd": null,
    "winRate": null,
    "matches": null,
    "updatedAt": null
  }
}
```

### R6 연결 해제

```http
DELETE /api/v1/r6/disconnect
Authorization: Bearer {accessToken}
```

성공 응답:

```json
{
  "success": true,
  "data": null
}
```

연결 해제는 `gameStats.R6`만 삭제한다. PUBG 및 다른 게임 데이터는 유지한다.

## 7. 저장 구조

R6 연결 정보는 `UserProfile.gameStats` JSON의 `R6` 키에 저장한다.

내부 저장 예시:

```json
{
  "PUBG": {
    "connected": true,
    "playerName": "PubgPlayer"
  },
  "R6": {
    "connected": true,
    "playerName": "R6Player",
    "playerNameNormalized": "r6player",
    "platform": "PC",
    "accountId": "internal-r6-platform-user-id",
    "tierLabel": "EMERALD II",
    "kd": 1.05,
    "winRate": 56.0,
    "matches": 117,
    "updatedAt": "2026-07-17T03:30:00+09:00"
  }
}
```

값이 `null`이면 해당 JSON 필드는 저장하지 않는다. `accountId`는 내부 식별자이므로 일반 사용자 프로필 응답을 만들 때 제거한다.

R6 데이터 갱신과 해제는 `gameStats`의 다른 게임 항목을 복사해 보존한 상태에서 `R6` 항목만 변경한다.

## 8. 오류 처리

| 상황 | GamerIN HTTP 상태 | 처리 정책 |
| --- | --- | --- |
| 로그인 정보 없음 | `401 Unauthorized` | R6 처리 중단 |
| 인증 사용자 DB row 없음 | `401 Unauthorized` | R6 처리 중단 |
| 사용자 프로필 미초기화 | `409 Conflict` | R6 처리 중단 |
| playerName 누락 또는 100자 초과 | `400 Bad Request` | 외부 API 호출 안 함 |
| R6Data 사용자 없음 | `404 Not Found` | 기존 저장값 유지 |
| 조회된 계정 ID가 연결 당시 ID와 불일치 | `409 Conflict` | 다른 계정 전적으로 갱신하지 않고 재연결 필요 |
| R6Data 요청 제한 | `429 Too Many Requests` | 기존 저장값 유지 |
| R6Data 키 누락 | `503 Service Unavailable` | 설정 오류로 처리 |
| R6Data `401` 또는 `403` | `503 Service Unavailable` | 설정 또는 키 오류로 처리 |
| R6Data 네트워크 또는 `5xx` | `502 Bad Gateway` | 기존 저장값 유지 |
| 필수 계정 ID 누락 또는 잘못된 숫자 | `502 Bad Gateway` | 비정상 외부 응답으로 처리 |

외부 API 응답을 모두 파싱한 다음 `UserProfile`을 갱신한다. 따라서 중간 호출이 실패하면 일부 전적만 저장되는 상태를 만들지 않는다.

## 9. 환경 설정

### 로컬 환경

파일:

```text
src/main/resources/application-local.yaml
```

설정:

```yaml
r6:
  api:
    key: "발급받은-R6Data-API-키"
    base-url: "https://api.r6data.com"
```

주의사항:

- 실제 키는 `application-local.example.yaml`에 넣지 않는다.
- `application-local.yaml`은 Git에 커밋하지 않는다.
- 키를 변경한 후 백엔드를 재시작한다.
- 키를 프론트 환경변수나 브라우저 요청에 포함하지 않는다.

### 운영 환경

```text
R6DATA_API_KEY={R6Data API key}
R6DATA_API_BASE_URL=https://api.r6data.com
```

`R6DATA_API_BASE_URL`은 생략할 수 있으며 기본값은 `https://api.r6data.com`이다.

### Docker 배포

현재 Docker Compose를 사용할 때는 백엔드 컨테이너 환경변수 전달을 확인해야 한다. `gamerin_docker/docker-compose.yml`의 backend `environment`에 다음 값이 필요하다.

```yaml
R6DATA_API_KEY: ${R6DATA_API_KEY}
R6DATA_API_BASE_URL: ${R6DATA_API_BASE_URL:-https://api.r6data.com}
```

서버의 `gamerin_docker/.env`에는 다음 값을 둔다.

```dotenv
R6DATA_API_KEY=replace-with-real-r6data-api-key
R6DATA_API_BASE_URL=https://api.r6data.com
```

실제 키는 `.env.example`이나 Git 저장소에 커밋하지 않는다.

## 10. 키 인증 확인

R6Data 키 자체는 다음 API로 확인할 수 있다.

```http
GET https://api.r6data.com/api/me/usage
api-key: {R6Data API key}
```

PowerShell 예시:

```powershell
$env:R6DATA_API_KEY = "발급받은-R6Data-API-키"

Invoke-RestMethod `
  -Uri "https://api.r6data.com/api/me/usage" `
  -Headers @{ "api-key" = $env:R6DATA_API_KEY }
```

- `200`: 키 인증 성공
- `401` 또는 `403`: 잘못된 키, 다른 서비스의 키 또는 비활성 키

## 11. 테스트

### 자동 테스트 범위

- R6Data `api-key` 헤더 검증
- `fullStats`, `seasonalStats` 쿼리 파라미터 검증
- 공백, `+` 등 playerName URL 인코딩 검증
- 현재 시즌 PC 경쟁전 선택
- 경쟁전 우선 선택 시 과거 시즌·일반전·콘솔 데이터 제외
- 현재 시즌 경쟁전 미보유 시 통합 일반전 fallback
- 일반전 통합값과 세부 세그먼트 중복 합산 방지
- `pvp_standard`, `pvp_unranked`, `pvp_casual` 별칭 fallback
- 최신 티어 history 선택
- K/D, 경기 수 및 승률 매핑
- 현재 시즌 경쟁전 0경기 계정의 일반전 fallback
- 설정 누락 처리
- `401`, `403`, `404`, `429`, `500`, `502` 변환
- 필수 계정 ID 누락과 잘못된 숫자 처리
- 외부 API 실패 시 기존 저장 전적 보존
- R6 연결 및 해제 시 PUBG 데이터 보존
- 공개 프로필에서 내부 식별자 제거
- 레거시 `trackerProfileId` 제거 및 비노출
- Controller 요청 검증과 응답 계약

### R6 관련 테스트

```powershell
.\gradlew.bat test `
  --tests "com.gamerin.backend.domain.r6.*" `
  --tests "com.gamerin.backend.domain.user.entity.UserProfileTest" `
  --tests "com.gamerin.backend.domain.user.service.UserServiceGameStatsTest"
```

### 전체 백엔드 테스트와 빌드

```powershell
.\gradlew.bat test
.\gradlew.bat build
git diff --check
```

2026-07-17 검증 결과:

- R6Data 키 인증 성공
- 실제 공개 PC 랭크 계정 `fullStats` 성공
- 실제 공개 PC 랭크 계정 `seasonalStats` 성공
- Java `R6DataStatsClient` 라이브 파싱 성공
- 실제 경쟁전 미보유 계정의 통합 일반전 경기 수와 K/D 확인
- 전체 테스트 317개, 실패 0, 오류 0
- 전체 빌드 성공
- `git diff --check` 성공

### 선택 실행형 라이브 테스트

`R6DataLiveIntegrationTest`는 일반 테스트와 CI에서 외부 API를 호출하지 않도록 기본 비활성화되어 있다.

실행에 필요한 환경변수:

```powershell
$env:R6DATA_LIVE_TEST = "true"
$env:R6DATA_API_KEY = "발급받은-R6Data-API-키"
$env:R6DATA_LIVE_PLAYER = "실제-PC-전적-닉네임"
$env:R6DATA_API_BASE_URL = "https://api.r6data.com"

.\gradlew.bat test `
  --tests "com.gamerin.backend.domain.r6.client.R6DataLiveIntegrationTest"
```

라이브 테스트는 다음 값을 실제 R6Data 응답에서 확인한다.

- 계정 ID
- 사용자명
- 경쟁전 선택 시 티어
- K/D
- 0~100 범위 승률
- 1 이상 경기 수

API 사용량을 소비하므로 일반 테스트마다 실행하지 않는다.

## 12. Swagger 검증 절차

1. 백엔드를 local profile로 실행한다.
2. Swagger UI에서 로그인 API로 access token을 발급받는다.
3. Swagger의 Authorize에 Bearer token을 입력한다.
4. `POST /api/v1/r6/connect`에 실제 PC Ubisoft 닉네임을 입력한다.
5. 응답이 `connected: true`인지 확인한다.
6. `GET /api/v1/r6/me`에서 티어, K/D, 승률, 경기 수를 확인한다.
   기존에 연결된 사용자도 재연결할 필요 없이 이 API를 한 번 호출하면 새 경쟁전 우선·일반전 fallback 정책으로 저장 전적이 갱신된다.
   일반전 fallback 응답에서는 `tierLabel`이 `null`이고 K/D, 승률, 경기 수는 일반전 값이어야 한다.
7. 사용자 프로필 응답에 `accountId`가 노출되지 않는지 확인한다.
8. `DELETE /api/v1/r6/disconnect`를 호출한다.
9. 다시 `GET /api/v1/r6/me`를 호출해 `connected: false`인지 확인한다.
10. PUBG 데이터가 유지되는지 확인한다.

## 13. 보안 및 운영 주의사항

- R6Data API 키는 백엔드 서버만 보유한다.
- 키를 Git, 로그, Swagger 요청 본문, 프론트 코드에 남기지 않는다.
- 오류 응답에 API 키나 R6Data 원문 응답 전체를 포함하지 않는다.
- `accountId`는 내부 연결 검증용이며 공개 프로필에서 제거한다.
- `GET /api/v1/r6/me`는 외부 API를 호출하므로 자동 새로고침 주기를 짧게 잡지 않는다.
- `429`가 발생하면 즉시 일반전 또는 다른 공급자로 fallback하지 않는다.
- R6Data 장애 시 기존 저장 전적을 유지하며 부분 갱신하지 않는다.

## 14. 현재 제한사항

- PC Ubisoft Connect만 지원한다.
- 콘솔, Steam 직접 조회 및 크로스 플랫폼 선택은 지원하지 않는다.
- 현재 시즌 경쟁전이 있으면 경쟁전을 제공하고, 없으면 통합 일반전 요약 통계를 제공한다.
- 티어 이미지 URL은 GamerIN API 응답에 포함하지 않는다.
- 캐시와 재시도 정책은 아직 없다.
- 외부 API 요청에 대한 별도 GamerIN rate limit은 아직 없다.
- 저장된 Ubisoft 닉네임이 변경되면 기존 닉네임 조회가 실패할 수 있으며 재연결이 필요할 수 있다.
- 닉네임이 다른 Ubisoft 계정에 재할당되어 조회된 `accountId`가 저장값과 다르면 전적 갱신을 거부한다.
- 일반 자동 테스트는 실제 외부 API를 호출하지 않으며, 경쟁전 우선·일반전 fallback·기록 없음 응답은 모의 HTTP fixture로 검증한다.

## 15. 변경 시 체크리스트

R6 관련 코드를 변경할 때 다음을 확인한다.

- [ ] `R6StatsClient` 공개 계약을 불필요하게 변경하지 않았는가
- [ ] Controller 주소와 응답 DTO를 유지했는가
- [ ] 현재 시즌 PC 경쟁전이 있으면 일반전보다 우선하는가
- [ ] 경쟁전이 없을 때 통합 일반전으로 fallback하는가
- [ ] 일반전 통합값과 세부값을 중복 합산하지 않는가
- [ ] 전적 없음과 외부 API 오류를 구분하는가
- [ ] 외부 API 오류 시 기존 저장값을 보존하는가
- [ ] PUBG와 다른 게임 데이터를 보존하는가
- [ ] `accountId`와 API 키가 외부로 노출되지 않는가
- [ ] 신규 Tracker 의존 코드가 추가되지 않았는가
- [ ] R6 테스트와 전체 테스트가 통과하는가
- [ ] 운영 환경에 `R6DATA_API_KEY`가 전달되는가
