# #55 개별 알림 읽음과 새 DM의 동시 갱신

대상: https://github.com/wishufeeldasame/gamerin_backend/issues/55

## 읽음 기준

`PATCH /api/v1/notifications/{notificationId}/read`는 서버가 유효한 알림을 조회했을 때 관찰한 이벤트만 읽음 처리한다. 조회 이후 새 DM으로 알림이 바뀌면 최신 메시지와 이벤트 시각을 보존하고 새 알림을 미읽음으로 유지한다. 조회 전에 도착해 이미 조회 결과에 포함된 DM은 읽음 대상이다. 클라이언트 화면에 표시됐던 버전을 전달받는 API는 아니다.

## 구현

- 기존 인증, 수신자 확인, 연관 객체의 삭제 상태 검증과 404 응답을 유지한다.
- 조회한 관리 엔티티를 변경하지 않고 `readAt`만 조건부 UPDATE한다. 알림 ID, 수신자 ID, 조회 당시 `eventAt`, null을 포함한 `messageId` 일치 및 미읽음 여부를 검사한다.
- 서로 다른 메시지의 시각이 같을 수 있으므로 `eventAt`만으로 이벤트를 구분하지 않는다. DB에서 읽은 시각을 그대로 비교한다.
- 갱신이 0건이면 정상 종료한다. 새 이벤트로 바뀐 경우, 이미 읽은 경우, 조회 후 삭제된 경우에 최신 이벤트를 재조회하여 읽거나 재시도하지 않는다.
- 읽음 갱신이 먼저 완료되면 이후 DM 갱신이 `readAt`을 다시 null로 만든다. DM 갱신이 먼저 완료되면 조건부 읽음 갱신은 이전 이벤트를 덮어쓰지 않는다.
- 전체 알림 읽음의 cutoff 정책과 대화 읽음 경로는 유지한다. 새 DB migration, 인덱스, 의존성 및 API 계약 변경은 없다.

bulk UPDATE 이후 조회했던 엔티티는 오래된 값을 가질 수 있다. 현재 서비스는 void로 종료하며 이를 다시 수정하거나 응답에 사용하지 않는다. 이후 같은 트랜잭션에서 엔티티를 재사용하는 흐름을 추가한다면 영속성 컨텍스트를 함께 검토해야 한다.

## 검증 범위

실제 `NotificationQueryService.markRead()`의 조회 직후를 latch로 제어하고 별도 트랜잭션의 `MessageService.sendMessage()`와 경합시킨다. PostgreSQL 테스트는 최신 메시지 ID, 이벤트 시각, 미읽음 상태를 확인한다.

- 조회 후 새 DM 커밋, 서로 다른 시각 및 같은 시각의 메시지
- 읽음 UPDATE가 실제 DB 행 잠금에서 대기한 뒤 새 DM 커밋 후 조건 재평가로 0건 갱신
- 개별 읽음 커밋 후 DM 갱신
- 조회 후 알림 삭제
- 일반 알림의 null 메시지 ID, 중복 읽음 시 최초 읽음 시각 유지, 타 수신자 404
- 기존 알림 API의 인증·읽음·전체 읽음과 DM 동시성 회귀

PostgreSQL 테스트는 기존 `NOTIFICATION_POSTGRES_TEST_URL`, `NOTIFICATION_POSTGRES_TEST_USERNAME`, `NOTIFICATION_POSTGRES_TEST_PASSWORD`로 활성화한다. 테스트는 임시 스키마를 생성·삭제하므로 반드시 테스트 전용 DB를 지정한다.

동시 UPDATE 대기 후 조건 재평가 동작: [PostgreSQL Read Committed 공식 문서](https://www.postgresql.org/docs/16/transaction-iso.html).

## 실행 결과 (2026-09-28)

- Java 21.0.10과 별도 PostgreSQL 16.13 테스트 인스턴스에서 검증했다. 운영 DB와 실제 외부 API는 사용하지 않았다.
- 기존 서비스 구현에서 새 DM 커밋 후 메시지 ID가 이전 값으로 돌아가는 실패를 두 케이스(서로 다른 시각 / 동일 시각) 모두 재현했다. 수정본에서는 두 케이스 모두 통과했다.
- 알림 PostgreSQL 테스트 31건 모두 통과했다. 이번에 추가한 6건에는 실제 UPDATE 잠금 대기, null 메시지 ID, 삭제 경합, 중복 읽음 및 수신자 제한 검증이 포함된다.
- 전체 JUnit 집계는 558건 중 522건 통과, 36건 건너뜀, 실패·오류 0건이다. 건너뛴 항목은 이번 실행에서 활성화하지 않은 게임 PostgreSQL 20건·게임 migration 4건·북마크 5건·해시태그 4건·시더 2건 및 실제 R6 API 1건이다.
- 실행 명령: `./gradlew.bat --offline --no-daemon --init-script D:/project/tmp/issue55-verify-20260928/test.init.gradle test`. 일반 테스트는 H2와 임시 업로드 경로를 사용한다. H2에는 V24가 제공하는 신고 번호 시퀀스를 테스트 초기화 설정으로 생성하고, 알림 PostgreSQL 테스트는 실제 Flyway migration을 적용했다.
- 별도 담당자의 제품 코드·테스트 독립 리뷰에서 추가 blocker는 발견하지 못했다. `git diff --check`를 통과했다.
- `./gradlew.bat --offline --no-daemon bootJar` 빌드를 통과했다.
- 테스트 임시 스키마가 남지 않았음을 확인하고 전용 DB를 종료했다. 실행 로그와 집계는 `D:/project/tmp/issue55-verify-20260928/`에 보관하며 이 경로는 제품 실행에 필요하지 않다.
