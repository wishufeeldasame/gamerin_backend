# Issue #52: 게임 전적 동시 저장 무결성

- 대상: [이슈 #52](https://github.com/wishufeeldasame/gamerin_backend/issues/52)
- 기준 커밋: `de9b9eaa9ec09d8fbf7e4e184b1b66ebf3c6791b` (`origin/develop`)
- 작업 브랜치: `fix/backend-stability`
- 전체 회귀 검증일: 2026-09-16
- 마이그레이션 번호 변경 검증일: 2026-09-17

## 범위와 저장 구조 결정

기존 `user_profiles.game_stats` JSONB와 게임별 엔티티 변경 메서드를 유지한다. 외부 API 호출 이후 짧은 저장 트랜잭션에서 최신 프로필 행을 잠그고 변경한다. 게임별 연동 변경 횟수를 별도 JSONB 컬럼에 저장해, 조회를 시작한 뒤 연동 상태가 바뀐 요청의 저장을 거부한다.

이번 작업은 다른 게임의 변경 덮어쓰기와 오래된 전적 조회에 의한 연동 복원을 막는다. B05 알림 읽음 경합, B07 중복 계정 연동의 DB 유일성, B08 기존 LoL 캐시 정리 정책, 별도 연동 테이블 전환은 포함하지 않는다.

| 선택지 | 이번 결정의 근거 |
| --- | --- |
| 프로필 전체 `@Version` | 충돌 감지는 가능하지만, 서로 다른 게임의 변경을 모두 보존하려면 재조회·재적용과 충돌 처리까지 필요하다. |
| JSONB 부분 갱신 | 게임 키별 갱신과 연동 상태 비교를 SQL에 구현해야 한다. 현재 엔티티의 Map 변경 로직과 저장 규칙을 중복시키지 않는다. |
| 별도 연동 테이블 | 데이터 이전과 여러 조회 경로 변경이 필요해 #52의 최소 변경 범위보다 크다. |
| **최신 행의 짧은 비관적 잠금 + 연동 변경 횟수** | 기존 JSON과 도메인 메서드를 재사용하면서 저장을 순서대로 적용하고 오래된 조회를 식별한다. 같은 사용자의 게임 저장은 잠깐 직렬화된다. |

## 처리 흐름

1. 게임 서비스는 `Propagation.NOT_SUPPORTED`로 실행한다. 호출자의 트랜잭션이 있으면 일시 중단한다.
2. `findWithProfileById`가 짧은 읽기 트랜잭션에서 사용자와 프로필을 함께 조회한다. 기존 `open-in-view: false` 설정에 따라 이 조회가 끝나면 스냅샷은 영속성 컨텍스트에서 분리된다.
3. 연동 식별자와 해당 게임의 연동 변경 횟수를 기억하고 외부 API를 호출한다. 이 구간에는 DB 트랜잭션이 없다.
4. 별도 Spring 빈인 `GameStatsPersistenceService`의 저장 트랜잭션에서 `PESSIMISTIC_WRITE`로 최신 프로필을 조회한다.
5. 연동·해제는 최신 프로필에 기존 변경 메서드를 적용한다. 전적 갱신은 현재 연동 여부, 계정 식별자, 연동 변경 횟수가 모두 일치할 때만 적용한다.
6. Hibernate가 변경된 JSON을 저장하고 커밋할 때 잠금을 해제한다. 비교와 저장 사이에 다른 게임 저장이 끼어들 수 없다.

`game_connection_versions`는 JPA의 `@Version`이 아니다. PUBG/R6/RIOT별로 연동·해제할 때만 증가하고 전적 갱신에는 증가하지 않는다. 해제 후에도 횟수를 남겨 같은 계정을 다시 연동한 경우까지 이전 조회와 구분한다. 기존 데이터에 키가 없으면 0으로 읽는다.

`UserProfile`의 `@DynamicUpdate`는 소개글·이미지 등 일반 프로필 수정이 과거에 읽은 `game_stats`를 함께 저장하는 경로를 막는다. 게임 저장은 위의 잠금 경로를 사용한다.

## 유지되는 정책과 동작

- 인증, 입력 검증, 중복 판정 기준과 기존 오류 응답을 유지한다.
- PUBG의 랭크 전적 부재 시 일반 전적 대체, R6의 플랫폼·계산 규칙, LoL의 큐 우선순위·KDA 계산을 유지한다.
- 공개 엔드포인트와 DTO 필드를 변경하지 않는다. 연동 변경 횟수는 내부 저장 메타데이터다.
- 오래된 조회는 DB에 저장하지 않으며 외부 API를 자동 재시도하지 않는다. PUBG/LoL은 요청에서 계산한 기존 형태의 응답을 반환하고, R6는 저장 시 확인한 최신 프로필로 응답한다.
- 같은 연동에서 겹친 전적 조회의 결과 순서에 별도의 최신 요청 우선 정책을 추가하지 않는다.
- Riot 계정 교체 시 이미 존재하던 LoL 캐시의 정리 정책은 바꾸지 않는다. 이번 변경은 교체 전에 시작한 조회가 교체 후 상태를 덮어쓰는 것을 막는다.

## 마이그레이션과 적용 조건

신규 마이그레이션은 홀수 번호를 사용하는 프로젝트 정책에 따라 V19 다음에 V21을 추가한다. V20은 비워 두며 기존 V1~V19의 번호와 내용은 유지한다.

`V21__add_game_connection_versions.sql`은 다음 컬럼만 추가한다.

```sql
ALTER TABLE user_profiles
    ADD COLUMN game_connection_versions JSONB NOT NULL DEFAULT '{}';
```

기존 `game_stats`를 재작성하거나 계정 소유자를 정하는 데이터 정리는 하지 않는다. 실제 PostgreSQL에서 V19 데이터에 V21을 적용해 기존 JSON과 소개글 보존을 검증했다.

새 코드가 요청을 처리하기 전에 V21이 적용되어야 한다. 운영 전환 시에는 이전 버전의 진행 중인 게임 저장 요청을 종료한 뒤 새 버전으로 전환해야 한다. 이전 버전은 연동 변경 횟수와 새 저장 절차를 사용하지 않으므로 두 버전의 쓰기가 공존하면 이 수정의 보장이 성립하지 않는다. 이번 작업에서는 배포하지 않았다.

## 실행한 검증

Java 21, PostgreSQL 16.13의 별도 로컬 테스트 인스턴스에서 검증했다. 테스트는 임의 이름의 전용 스키마를 생성·정리하고, 외부 게임 API 응답은 모의 객체로 제어한다.

### 정책 재검토에서 보완한 사항

- `RiotService.getLolSummary`의 저장 호출을 외부 API 예외 변환 블록 밖으로 이동했다. 별도 저장 트랜잭션의 DB 오류가 502로 바뀌던 회귀를 제거하고 기존 공통 서버 오류 처리로 전달한다. 외부 API의 기존 상태 코드·메시지는 유지한다. 수정 전 회귀 테스트 3개 중 DB 오류 전파 1개가 실패했고, 수정 후 3개 모두 통과했다.
- 새 PostgreSQL 테스트를 `SpringJUnitConfig`와 명시적인 JPA/Flyway/게임 서비스 설정으로 제한했다. 전체 애플리케이션이나 `local` 설정을 로드하지 않고 실제 저장·트랜잭션 프록시는 사용한다. 전용 스키마 사용과 스케줄러 미등록을 검증하는 테스트도 추가했다.
- 관련 PostgreSQL·마이그레이션·게임 서비스·엔티티 테스트 67개를 실행해 모두 통과했다.
- 전체 회귀 검증용 임시 Gradle 초기화 스크립트는 DB를 H2 메모리 DB, 업로드를 임시 테스트 디렉터리로 제한한다. #52 PostgreSQL 테스트는 우선순위가 높은 동적 속성으로 별도 PostgreSQL 스키마를 사용한다. 실제 API 키를 주입하지 않으며 기존 비밀 설정 파일의 내용은 열람·검색·출력·복사·수정하지 않았다.
- 첫 전체 검증 시도에서는 임시 실행 설정이 기존 테스트 설정을 대체해 검열 API 미설정과 업로드 기본값 차이로 16개가 실패했다. 제품 코드와 검열 정책을 수정하지 않고 실행 설정을 바로잡아 재검증했다.

| 검증 | 결과 |
| --- | --- |
| 수정 전 재현 테스트 | 최초 17개 중 16개 실패로 기존 문제 확인 |
| 최종 PostgreSQL 동시성·격리 테스트 | 28개 통과, 미실행 0개 |
| PostgreSQL V19 → V21 마이그레이션 테스트 | 1개 통과 |
| 정책 재검토 후 전체 `test bootJar` | 총 497개 중 462개 통과, 35개 미실행, 실패·오류 0개, 빌드 성공 |

동시성 테스트는 latch로 `A 조회 → B 커밋 → A 저장` 순서를 고정한다. 다음을 포함한다.

- 이슈의 LoL 조회 중 R6 연동 재현과 실제 JSONB 컬럼 확인
- 세 게임 각각의 다른 게임 동시 갱신·연동 보존
- 조회 중 해제, 다른 계정 교체, 같은 계정 해제 후 재연동
- 오래된 일반 프로필 수정과 게임 저장의 경합
- 외부 API 호출 시 트랜잭션 부재와 호출자 트랜잭션 일시 중단
- 저장 실패 시 JSON과 연동 변경 횟수의 동시 롤백
- 연동 변경 횟수가 없는 기존 데이터의 조회·해제

전체 검증에서 미실행된 35개는 별도 환경변수가 필요한 기존 Bookmark 5개, Hashtag 4개, Notification 25개, R6 실제 API 1개다. 이번 #52 PostgreSQL 테스트 29개는 모두 실행했다.

검증 후 #52 테스트 스키마가 남아 있지 않음을 확인하고 전용 PostgreSQL 인스턴스를 종료했다. 기존 Flyway V1~V19, API controller/DTO, 인증·중복 판정·전적 계산 규칙과 제품 설정에는 추가 변경이 없다. API/DB 설명과 배포 조건은 이 문서에 기록했으며 frontend와 Docker 변경은 필요하지 않다.

### 재실행

테스트용 PostgreSQL 연결을 아래 환경변수에 설정한다. 테스트 계정에는 전용 스키마를 생성·삭제할 권한이 필요하다.

- `GAME_STATS_POSTGRES_TEST_URL`: 테스트 DB의 JDBC URL
- `GAME_STATS_POSTGRES_TEST_USERNAME`: 테스트 DB 사용자
- `GAME_STATS_POSTGRES_TEST_PASSWORD`: 테스트 DB 암호

```powershell
.\gradlew.bat test --tests '*GameStatsPostgresConcurrencyTest' --tests '*GameConnectionVersionsMigrationTest' --tests '*RiotServiceTest'
.\gradlew.bat test bootJar
```

검증 당시에는 캐시된 의존성으로 `--offline`을 추가해 실행했다. 테스트 보고서는 `build/reports/tests/test/index.html`에 생성된다. 위 환경변수 없이 실행하면 새 PostgreSQL 테스트도 조건에 의해 미실행되므로 일반 테스트 성공만으로 동시성 검증 완료를 판단하면 안 된다.

전체 테스트 명령은 먼저 DB와 업로드 경로가 테스트 전용인지 확인하고 실행한다. 이번 재검증은 `--init-script D:/project/tmp/game-concurrency-plan-20260916/issue52-policy-test.init.gradle`로 테스트 프로세스의 연결과 파일 경로를 제한했다. 이 스크립트는 로컬 검증용이며 제품 설정을 변경하지 않는다.

## 이슈 기록용 요약

> `game_stats` JSONB를 유지하고, 외부 API 호출과 DB 저장 트랜잭션을 분리했습니다. 저장 시 최신 프로필을 짧게 잠가 다른 게임의 변경을 보존하며, 게임별 연동 변경 횟수와 계정 식별자를 검사해 해제·계정 교체·같은 계정 재연동 이전에 시작한 전적 조회의 저장을 막습니다. 일반 프로필 수정에는 `@DynamicUpdate`를 적용했습니다. PostgreSQL 동시성·격리·마이그레이션 29개 테스트를 통과했습니다. 정책 재검토에서 DB 오류 전파를 보완하고 테스트의 로컬 설정·스케줄러 의존성을 제거했습니다. V21 적용과 이전 버전의 진행 중인 쓰기 종료가 배포 조건입니다.

위 요약은 GitHub 이슈의 설계 결정 기록으로 사용할 수 있다. 이슈 댓글은 별도로 게시하지 않았다.

## 설계 근거

- [Spring Data JPA: Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html): repository 쿼리의 잠금 모드 지정.
- [PostgreSQL 16: Transaction Isolation](https://www.postgresql.org/docs/16/transaction-iso.html): Read Committed에서 잠금 대기 후 갱신된 행을 처리하는 동작.
- [Hibernate 6.6: DynamicUpdate](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/annotations/DynamicUpdate.html): 실제 변경된 컬럼을 UPDATE에 포함하는 동작.
- [Spring Framework: Transactional annotations](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html): 프록시를 거치는 별도 빈 호출로 저장 트랜잭션 적용.
- [Spring Boot 3.5: Testing Spring Boot Applications](https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html): 기본 전체 애플리케이션 검색과 명시적 테스트 구성의 차이.
