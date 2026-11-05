-- 신고 단계(stage) 도입: 같은 신고자가 같은 대상에 단계별로 1건씩 신고할 수 있게 한다.
-- 기존 (reporter_id, target_type, target_id) 유니크는 ONGOING 단계 신고가 반려되면
-- FINISHED 단계 이의제기까지 막아 버리므로 stage를 포함하도록 교체한다.
-- stage는 NOT NULL DEFAULT 'GENERAL'이어야 한다 (NULL이면 PostgreSQL 유니크가 중복을 막지 못한다).
ALTER TABLE reports
    ADD COLUMN stage VARCHAR(20) NOT NULL DEFAULT 'GENERAL'; -- GENERAL, ONGOING, FINISHED

-- 기존 멘토링 신청 건 신고 백필: 신청 건이 종료 보고(FINISHED) 상태면 FINISHED, 그 외는 ONGOING
UPDATE reports r
SET stage = CASE WHEN a.status = 'FINISHED' THEN 'FINISHED' ELSE 'ONGOING' END
FROM mentoring_applications a
WHERE r.target_type = 'MENTORING'
  AND a.id = r.target_id;

DROP INDEX IF EXISTS uk_reports_reporter_target;
CREATE UNIQUE INDEX uk_reports_reporter_target_stage ON reports (reporter_id, target_type, target_id, stage);
