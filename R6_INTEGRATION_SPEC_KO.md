# GamerIN R6 전적 연동 명세

## 1. 범위와 원칙

이 문서는 PR2까지 구현된 Rainbow Six Siege 전적 연동 계약을 정의한다.

| 항목 | 정책 |
| --- | --- |
| 외부 공급자 | R6Data API |
| 지원 플랫폼 | PC Ubisoft Connect |
| R6Data 플랫폼 값 | `uplay` |
| 외부 인증 | 백엔드가 `api-key` 헤더 전송 |
| 저장 방식 | `UserProfile.gameStats` JSON의 `R6` 항목 |
| DB 스키마 변경 | 없음 |

- R6 연결·저장 전적 조회·명시적 갱신·해제 API를 제공한다.
- 외부 연동은 `R6StatsClient` 뒤에 격리하고 구현체는 `R6DataStatsClient`를 사용한다.
- PUBG 등 다른 게임 데이터와 사용자·인증 정책은 변경하지 않는다.
- 모든 GamerIN R6 API는 Bearer 인증이 필요하다.

## 2. 처리 구조

저장 전적 조회 흐름은 `R6Controller -> R6Service -> UserProfile`이며 외부 API를 거치지 않는다.
연결·갱신 흐름은 `R6Controller -> R6Service -> R6StatsClient -> R6DataStatsClient -> R6Data API`이고, 받은 응답의 전적 선택과 변환은 `R6DataStatsParser`가 담당한다.

- `R6Controller`: 네 HTTP API와 공통 응답 형식을 제공한다.
- `R6Service`: 인증 사용자 확인, 입력 정규화, 저장 전적 조회, 계정 연결·갱신·해제를 담당한다.
- `R6DataStatsClient`: URI 인코딩, 외부 호출, 계정 ID 검증, 외부 오류 변환을 담당한다.
- `R6DataStatsParser`: 현재 시즌 경쟁전 선택, 일반전 fallback, 티어·K/D·승률·경기 수 변환을 담당한다.
- `UserProfile`: 다른 게임 값을 복사해 보존한 뒤 `R6` 항목만 변경한다.

## 3. R6Data 호출 계약

기본 URL은 `https://api.r6data.com`이며 모든 요청에 `api-key: {R6Data API key}` 헤더를 사용한다.

기본 전적 요청은 `GET /api/stats?type=fullStats&nameOnPlatform={playerName}&platformType=uplay&modes=all`이다.
`nameOnPlatform`은 URI 템플릿으로 안전하게 인코딩한다.

주요 사용 필드는 다음과 같다.

- `data.platformInfo.platformUserId`: 연결 계정의 내부 식별자
- `data.platformInfo.platformUserHandle`: 외부 응답상의 닉네임
- `data.metadata.currentSeason`, 최상위 `seasonNumber`: 현재 시즌 판정
- `data.segments`: 경쟁전 및 일반전 통계
- `platform_families_full_profiles`: 경쟁전 보드 통계

경쟁전 통계가 선택됐지만 티어 이름을 얻지 못했을 때만
`GET /api/stats?type=seasonalStats&nameOnPlatform={playerName}&platformType=uplay`를 추가한다.

`data.history.data`에서 날짜가 가장 최신인 항목의 `metadata.rank`를 티어로 사용한다.
따라서 한 번의 연결 또는 명시적 갱신은 보통 `fullStats` 1회, 필요할 때만 총 2회 호출한다.

## 4. 전적 선택과 값 변환

### 4.1 경쟁전 우선 선택

현재 시즌은 다음 우선순위로 결정한다.

1. `data.metadata.currentSeason`
2. 최상위 `seasonNumber`
3. 경쟁전 세그먼트에 있는 가장 큰 시즌 번호

경쟁전 후보는 다음 조건을 적용한다.

- `attributes.gamemode == "pvp_ranked"`
- `attributes.platform`이 없거나 `pc`
- `attributes.sessionType`이 없거나 `ranked`
- 현재 시즌을 알 수 있으면 해당 시즌과 일치

같은 시즌 후보가 여러 개면 `rankPoints.value`가 큰 항목을 선택한다.
`matchesPlayed`가 없으면 보드의 `wins + losses + abandon`을 경기 수로 사용한다.
경기 수가 없거나 0이면 경쟁전 기록이 없는 것으로 처리한다.

티어는 세그먼트의 rank 메타데이터, rankPoints 메타데이터, 보드의 `rank_name`,
`seasonalStats` 최신 기록 순으로 찾는다.

### 4.2 일반전 fallback

현재 시즌 경쟁전 경기 수가 없거나 0일 때만 일반전 통계를 사용한다.

1. `pvp_quickplay` 통합 세그먼트를 우선한다.
2. 없으면 `pvp_standard`, `pvp_unranked`, `pvp_casual` 중 경기 수가 가장 큰 세그먼트를 고른다.
3. 같은 우선순위와 경기 수라면 시즌 번호가 큰 세그먼트를 고른다.

일반전도 플랫폼 값이 없거나 `pc`인 세그먼트만 사용한다.
`pvp_quickplay`는 통합값이므로 하위 모드와 합산하지 않는다.
일반전 fallback의 `tierLabel`은 항상 `null`이다.
경쟁전과 일반전 기록이 모두 없으면 네 요약 값 모두 `null`이다.

### 4.3 공개 값 계산

| 필드 | 원본 또는 계산 규칙 |
| --- | --- |
| `tierLabel` | 선택한 경쟁전 티어, 일반전이면 `null` |
| `kd` | 선택 세그먼트의 `stats.kdRatio.value`, 소수 셋째 자리부터 버림 |
| `matches` | `matchesPlayed`, 없으면 승+패+이탈 |
| `winRate` | `wins / (wins + losses) * 100`, 가장 가까운 정수로 반올림 |

승률 분모에는 이탈을 넣지 않는다. 승+패가 0이거나 필요한 값이 없으면 승률은 `null`이다.
음수 경기 수, 비정상 숫자, `NaN`·무한대는 잘못된 외부 응답으로 처리한다.

## 5. GamerIN API

공통 헤더는 `Authorization: Bearer {accessToken}`이다.

### 5.1 계정 연결

`POST /api/v1/r6/connect`에 `{"playerName":"R6Player"}` JSON을 전송한다.

- 닉네임의 앞뒤 공백을 제거하며, 빈 값과 100자 초과는 거부한다.
- `fullStats`를 호출해 `platformUserId`와 초기 전적을 확인한 뒤 저장한다.
- 저장 닉네임은 요청값을 정리한 값이고, 검색용 값은 소문자로 별도 저장한다.
- 성공 응답 데이터는 `connected`, `playerName`, `platform`만 포함한다.

### 5.2 내 저장 전적 조회

```http
GET /api/v1/r6/me
```

- 연결되지 않았거나 저장된 `accountId`가 없으면 외부 호출 없이 연결되지 않은 응답을 반환한다.
- 연결됐으면 `UserProfile.gameStats.R6`에 저장된 전적을 즉시 반환하며, R6Data를 호출하지 않고 DB 값과 `updatedAt`도 변경하지 않는다.
- 성공 응답 필드는 `game`, `connected`, `playerName`, `platform`, `tierLabel`, `kd`,
  `winRate`, `matches`, `updatedAt`이다.

### 5.3 내 전적 갱신

`POST /api/v1/r6/me/refresh`는 요청 본문을 사용하지 않는다.

- 연결되지 않았거나 저장된 `accountId`가 없으면 외부 호출 없이 연결되지 않은 응답을 반환한다.
- 저장된 닉네임으로 R6Data를 호출하고 응답의 `platformUserId`를 저장된 `accountId`와 비교한다.
- 계정 ID가 일치할 때만 전적과 `updatedAt`을 한 번에 저장하고 5.2와 같은 응답 DTO를 반환한다.
- 계정 ID가 다르면 `409`를 반환하며, 외부 호출·파싱·타임아웃 등 다른 실패도 기존 저장값을 변경하지 않는다.

### 5.4 계정 연결 해제

```http
DELETE /api/v1/r6/disconnect
```

`gameStats.R6` 항목만 삭제하고 `success: true`, `data: null`을 반환한다.

## 6. 저장과 일관성

연결된 R6 정보는 다음 구조로 저장한다.

```json
{
  "R6": {
    "connected": true,
    "playerName": "R6Player",
    "playerNameNormalized": "r6player",
    "platform": "PC",
    "accountId": "r6-platform-user-id",
    "tierLabel": "EMERALD II",
    "kd": 1.05,
    "winRate": 56.0,
    "matches": 117,
    "updatedAt": "2026-07-17T03:30:00+09:00"
  }
}
```

- nullable 값은 JSON에 `null`로 쓰지 않고 해당 키를 제거한다.
- 연결 및 명시적 갱신이 성공할 때 `updatedAt`을 서버 현재 시각으로 기록한다.
- 연결·갱신·해제 시 다른 게임 항목은 보존한다.
- 외부 호출 또는 파싱이 실패하면 R6 저장값을 부분 갱신하지 않는다.
- 이전 `trackerProfileId`는 성공적인 연결 또는 갱신 시 제거한다.
- `accountId`와 `trackerProfileId`는 R6 응답 및 공개 프로필에서 노출하지 않는다.

## 7. 오류 계약

| 상황 | HTTP 상태 | 저장값 처리 |
| --- | --- | --- |
| 인증 정보 또는 인증 사용자 없음 | `401 Unauthorized` | 변경 없음 |
| 사용자 프로필 미초기화 | `409 Conflict` | 변경 없음 |
| 닉네임 누락·공백·100자 초과 | `400 Bad Request` | 외부 호출 없음 |
| R6Data 계정 없음 (`404`) | `404 Not Found` | 변경 없음 |
| 저장 계정과 갱신 계정 ID 불일치 | `409 Conflict` | 변경 없음 |
| R6Data 요청 제한 (`429`) | `429 Too Many Requests` | 변경 없음 |
| API 키·기본 URL 누락 | `503 Service Unavailable` | 변경 없음 |
| R6Data 인증 실패 (`401`, `403`) | `503 Service Unavailable` | 변경 없음 |
| 연결·응답 읽기 타임아웃 | `502 Bad Gateway` | 변경 없음 |
| 네트워크 오류, 외부 `5xx`, 기타 호출 실패 | `502 Bad Gateway` | 변경 없음 |
| 필수 accountId 누락 또는 잘못된 숫자 | `502 Bad Gateway` | 변경 없음 |

오류 응답에는 API 키나 R6Data 원문 응답 전체를 포함하지 않는다. `GET /api/v1/r6/me`는 R6Data 상태와 무관하게 저장값을 반환하며, 갱신 오류가 발생해도 이후 조회에서 기존 값을 사용할 수 있다.

## 8. 환경 설정과 보안

애플리케이션 설정은 다음 환경 변수를 사용한다.

```text
R6DATA_API_KEY={R6Data API key}
R6DATA_API_BASE_URL=https://api.r6data.com
R6DATA_API_CONNECT_TIMEOUT=3s
R6DATA_API_READ_TIMEOUT=10s
```

`R6DATA_API_BASE_URL`을 생략하면 기본 URL을 사용하며 API 키가 비어 있으면 R6 호출은 `503`이다.
연결 타임아웃은 `r6.api.connect-timeout`, 응답 읽기 타임아웃은 `r6.api.read-timeout`이며 환경 변수를 생략하면 각각 3초와 10초를 사용한다. 타임아웃은 `502`로 변환하고, 로컬 예시는 `application-local.example.yaml`의 `r6.api` 설정을 따른다.

- 실제 키는 Git, 예제 파일, 로그, Swagger 요청 본문, 프론트 코드에 넣지 않는다.
- 키는 백엔드 실행 환경에만 주입하고 R6Data 요청 헤더에만 사용한다.
- Docker 배포 시 백엔드 컨테이너에도 네 환경 변수를 전달한다.
- 사용자 입력은 URI 구성기를 통해 인코딩하며 문자열로 URL을 조립하지 않는다.

## 9. 검증 범위와 운영 제한

자동 테스트는 API 계약, 입력 검증, 경쟁전 선택, 일반전 fallback, 통계 계산, 외부 오류·타임아웃 매핑,
계정 ID 불일치, 실패 시 저장값 보존, 다른 게임 데이터 보존, `GET /me`와 `POST /me/refresh`의 책임 분리를 검증한다.

```powershell
.\gradlew.bat test
.\gradlew.bat build
git diff --check
```

실제 R6Data를 호출하는 라이브 검증은 기본 비활성화하며 전용 환경 변수와 테스트 계정이 있을 때만 실행한다.

현재 운영 제한은 다음과 같다.

- PC Ubisoft Connect 외 콘솔·Steam 직접 조회와 플랫폼 선택은 지원하지 않는다.
- 티어 이미지 URL은 GamerIN 응답에 포함하지 않는다.
- `GET /api/v1/r6/me`는 저장값만 조회하므로 R6Data 장애나 지연의 영향을 받지 않으며, 외부 장애나 타임아웃 시 연결·갱신 요청은 오류를 반환하지만 이전 R6 저장값은 보존한다.
- R6Data 장애 시 다른 공급자로 대체하거나 일반전 값으로 오류를 숨기지 않는다.
- Ubisoft 닉네임이 다른 계정에 재할당되면 계정 ID 검증으로 갱신을 거부하며 재연결이 필요하다.
