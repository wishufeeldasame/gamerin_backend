# #53 R6·Riot 중복 연동 방지 배포 절차

대상: [PR #59](https://github.com/wishufeeldasame/gamerin_backend/pull/59), [이슈 #53](https://github.com/wishufeeldasame/gamerin_backend/issues/53).

V23은 연결된 R6 계정의 `LOWER(accountId)`와 Riot 계정의 `puuid`에 부분 유일 인덱스를 추가한다. 사전 중복 조회와 DB 인덱스의 비교 규칙은 같다. 기존 중복이 있으면 migration이 실패하는 것은 데이터를 임의로 삭제하거나 소유자를 선택하지 않기 위한 동작이다. 아래 사전 점검과 정리는 **V23 적용 전 별도 운영 단계**다.

## 1. 대상과 배포 조건 확인

- 담당자가 대상 DB·스키마, 백업과 복구 방법, 작업 시간대를 확인한다. 비밀번호를 명령행이나 작업 기록에 남기지 않는다.
- #52의 저장 트랜잭션·연결 버전과 V21이 선행되어야 한다. 병합 대상의 migration 번호 충돌과 실제 Flyway 이력을 확인한다. 이미 적용된 SQL 파일은 수정하지 않는다.
- 일반 `CREATE UNIQUE INDEX`는 생성 중 `user_profiles` 쓰기를 차단한다. 일반 프로필 수정까지 영향을 받으므로 테이블 크기와 허용 중단 시간을 확인한다.
- 기본 절차는 유지보수 구간 배포다. 기존 서버의 진행 중인 게임 API 요청과 DB 쓰기가 모두 끝난 뒤 모든 서버·배치·관리 도구의 프로필 쓰기를 중단하고 최종 점검한다. HTTP 요청 차단만으로 이미 실행 중인 작업이 종료되지는 않는다.
- 점검·정리 이후 인덱스 적용까지 쓰기 통제를 유지한다. 그렇지 않으면 점검 직후에도 새 중복이 생길 수 있다.

## 2. 읽기 전용 사전 점검

PostgreSQL 16의 승인된 운영 연결에서 다음 SQL을 실행한다. `current_schema()`가 실제 애플리케이션 테이블의 스키마인지 먼저 확인한다. psql 사용 시 `-X -v ON_ERROR_STOP=1`을 지정해 개인 시작 설정과 오류 후 계속 실행을 방지한다. 결과의 계정 식별자·사용자 ID는 제한된 운영 기록으로만 보관하고 PR이나 일반 로그에 게시하지 않는다.

```sql
BEGIN READ ONLY;

SELECT current_database(), current_schema(), current_user;

-- 결과가 있으면 연결 소유자 결정과 명시적 정리가 필요하다.
SELECT 'R6' AS game,
       LOWER(game_stats -> 'R6' ->> 'accountId') AS account_identifier,
       COUNT(*) AS owners,
       ARRAY_AGG(user_id ORDER BY user_id) AS user_ids
FROM user_profiles
WHERE COALESCE((game_stats -> 'R6' ->> 'connected')::boolean, false) = true
  AND game_stats -> 'R6' ->> 'accountId' IS NOT NULL
GROUP BY LOWER(game_stats -> 'R6' ->> 'accountId')
HAVING COUNT(*) > 1
UNION ALL
SELECT 'RIOT', game_stats -> 'RIOT' ->> 'puuid', COUNT(*),
       ARRAY_AGG(user_id ORDER BY user_id)
FROM user_profiles
WHERE COALESCE((game_stats -> 'RIOT' ->> 'connected')::boolean, false) = true
  AND game_stats -> 'RIOT' ->> 'puuid' IS NOT NULL
GROUP BY game_stats -> 'RIOT' ->> 'puuid'
HAVING COUNT(*) > 1;

-- NULL은 유일 인덱스가 막지 않는다. 누락·공백 식별자는 별도 확인한다.
SELECT user_id, 'R6' AS game
FROM user_profiles
WHERE COALESCE((game_stats -> 'R6' ->> 'connected')::boolean, false) = true
  AND NULLIF(BTRIM(game_stats -> 'R6' ->> 'accountId'), '') IS NULL
UNION ALL
SELECT user_id, 'RIOT'
FROM user_profiles
WHERE COALESCE((game_stats -> 'RIOT' ->> 'connected')::boolean, false) = true
  AND NULLIF(BTRIM(game_stats -> 'RIOT' ->> 'puuid'), '') IS NULL;

COMMIT;
```

`connected`를 boolean으로 변환할 수 없으면 조회가 실패한다. 성공한 점검으로 취급하지 말고, 같은 세션에 남은 트랜잭션은 `ROLLBACK`한 뒤 원인을 조사한다. 임의로 `false`로 바꾸거나 오류 행을 제외하여 배포를 진행하지 않는다. NULL·공백 식별자 결과도 담당자가 확인하고 처리 결정을 기록한다. 이 문서는 식별자 필수화나 탈퇴 사용자 제외 등 기존 정책을 변경하지 않는다.

## 3. 중복 소유자 결정 및 정리

1. 각 중복 그룹의 사용자와 연결 사실을 확인해 유지할 연결과 해제할 연결을 명시적으로 결정한다. 먼저 생성된 사용자나 UUID 정렬 순서만으로 소유자를 선택하지 않는다. 결정할 수 없으면 배포를 중단한다.
2. 일반 계정의 자발적 해제는 해당 사용자 인증으로 `DELETE /api/v1/r6/disconnect` 또는 `DELETE /api/v1/riot/disconnect`를 사용한다. 이 API는 관리자용 타 사용자 해제 API가 아니다. 유지보수 전 정리 또는 일반 트래픽을 차단한 승인된 작업 창에서만 수행한다.
3. API 사용이 불가능한 계정은 대상 사용자·계정·변경 전 값을 확정한 별도 승인 작업으로 정리한다. 이 문서의 조회 결과를 곧바로 일괄 UPDATE/DELETE에 연결하지 않는다. 수동 정리도 `UserProfile.disconnectR6/disconnectRiot`의 의미를 보존해야 한다: R6 키 제거, Riot은 RIOT·LOL 키 제거, 해당 `game_connection_versions` 증가, 다른 게임·프로필 정보 보존. 변경 전후 검증과 트랜잭션 롤백 절차를 함께 준비한다.
4. 정리 후 모든 프로필 쓰기를 중단하고 진행 중 작업 종료를 확인한다. **새 트랜잭션으로** 사전 점검을 다시 실행한다. 중복 결과가 0행이고 나머지 이상 데이터의 처리 결정이 완료된 경우에만 V23을 적용한다.

운영 데이터를 자동 정리하는 migration은 제공하지 않는다. 소유권 결정 없이 연결을 잃게 만드는 동작은 이 버그 수정의 범위가 아니다.

## 4. V23 적용과 서비스 재개

- 정상 배포 경로의 Flyway로 V23을 적용한다. SQL을 수동 실행한 뒤 Flyway 이력을 임의 삽입하지 않는다.
- 적용 중 다른 구버전 인스턴스를 재기동하지 않는다. 인덱스만 생기고 구버전이 요청을 처리하면 중복 요청이 409 대신 500으로 반환될 수 있다.
- 같은 DB·스키마에서 다음 조회를 실행한다. Flyway V23 성공 행 하나와 이름이 일치하는 인덱스 두 개를 확인하고, 두 인덱스의 `indisunique`, `indisvalid`, `indisready`가 모두 true인지 확인한다. `index_definition`도 원본 V23의 식·조건과 대조한다.

```sql
SELECT version, description, success
FROM flyway_schema_history
WHERE version = '23';

SELECT index_class.relname AS index_name,
       index_state.indisunique, index_state.indisvalid, index_state.indisready,
       pg_get_indexdef(index_class.oid) AS index_definition
FROM pg_index index_state
JOIN pg_class index_class ON index_class.oid = index_state.indexrelid
JOIN pg_class table_class ON table_class.oid = index_state.indrelid
JOIN pg_namespace table_schema ON table_schema.oid = table_class.relnamespace
WHERE table_schema.nspname = current_schema()
  AND table_class.relname = 'user_profiles'
  AND index_class.relname IN (
      'uq_user_profiles_connected_r6_account',
      'uq_user_profiles_connected_riot_puuid'
  );
```

- 409 변환 코드를 포함한 새 서버의 정상 기동을 확인한 후 쓰기를 재개한다. 동시 연동 검증은 별도 테스트 환경에서 수행하고 실제 사용자 계정으로 경쟁 요청을 만들지 않는다.
- 무중단 배포가 필수라면 409 처리 코드 선배포와 제약 적용을 분리해 별도 설계한다. `CONCURRENTLY`는 트랜잭션 안에서 실행할 수 없고 실패 시 invalid 인덱스가 남을 수 있으므로 현재 V23에 키워드만 추가하지 않는다.

## 5. 실패와 복구

- 현재 V23의 두 인덱스는 일반 Flyway 트랜잭션으로 적용된다. 중복·잘못된 연결 값·잠금 제한 등으로 실패하면 서비스 재개를 보류하고 원인, V23 이력과 두 인덱스 상태를 확인한다. 정상 트랜잭션 실패라면 V23 및 인덱스 변경이 롤백된다.
- 중복이면 3단계의 소유자 결정·정리를 수행하고 최종 점검부터 다시 진행한다. 잠금 문제면 장기 실행 쓰기를 확인하고 작업 창을 다시 확보한다. 시간 제한을 무조건 늘리지 않는다.
- 실패를 숨기기 위해 제약 제거, migration 체크섬 변경, 무조건적인 Flyway repair/baseline을 실행하지 않는다. 이력이 예상과 다르면 별도 원인 분석 후 복구한다.
- 새 앱을 롤백해야 해도 유일 인덱스를 자동 삭제하지 않는다. 기존 중복이 다시 저장될 수 있다. #52 호환성과 구버전의 500 응답 가능성을 평가해 유지보수 상태에서 복구 여부를 결정한다.
- 정리한 연결을 되돌리는 것은 앱 롤백과 별개다. 변경 전 기록을 바탕으로 소유권과 유일성을 다시 확인한 승인 작업으로만 복원한다.

## 검증 범위와 근거

- `ConnectedGameAccountIndexesMigrationTest`는 기존 중복 시 실패·데이터 보존·두 인덱스 롤백과 명시적 정리 후 재적용을 검증한다.
- `GameStatsPostgresConcurrencyTest`는 실제 PostgreSQL 저장과 200/409 응답, 실패한 계정 교체의 롤백을 검증한다.
- Copilot의 R6 테스트 실패 지적은 `23503`과 `23505`를 구분하지 않은 오탐이다. 해당 테스트는 유지한다. `23505`와 해당 인덱스명이 모두 일치할 때만 409로 변환한다.
- [PostgreSQL 16 CREATE INDEX](https://www.postgresql.org/docs/16/sql-createindex.html), [오류 코드](https://www.postgresql.org/docs/16/errcodes-appendix.html).

### 문서 SQL 확인 (2026-09-24)

별도 로컬 PostgreSQL 16에 합성 데이터 4행을 넣고 위 SQL 블록을 그대로 실행했다. R6 대소문자 중복과 Riot 정확 일치 중복을 각각 1그룹 검출했고, 비연결 R6 및 대소문자만 다른 Riot 식별자는 중복에 포함되지 않았다. 누락 R6 식별자와 공백 Riot 식별자도 검출했다. 테스트 중복 정리 후 원본 V23 SQL로 생성한 두 인덱스의 unique/valid/ready 상태와 정의를 조회했다. 이 검증의 Flyway 이력 테이블은 조회문 확인용 fixture이며 실제 Flyway 실행 검증을 대신하지 않는다. 제품 코드와 기존 테스트는 변경하지 않았고, 운영 DB 조회·정리·배포는 수행하지 않았다.
