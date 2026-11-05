-- 배포 전 점검 쿼리 (읽기 전용): 멘티가 낸 미결 신고 때문에 자동 정산이 보류될 FINISHED 신청 건
-- 신고자가 멘티면 정산이 계속 보류되고(어드민 처리 대상), 멘토면 코드 변경 후 보류에서 풀린다.
SELECT a.id                                   AS application_id,
       a.updated_at                           AS finished_at,
       (CURRENT_DATE - a.updated_at::date)    AS days_since_finished,
       r.report_code,
       r.status                               AS report_status,
       r.stage,
       CASE WHEN r.reporter_id = a.mentee_id THEN 'MENTEE (정산 계속 보류)'
            ELSE 'MENTOR (보류 해제)' END     AS reporter_role,
       r.created_at                           AS reported_at
FROM mentoring_applications a
JOIN reports r
  ON r.target_type = 'MENTORING'
 AND r.target_id = a.id
 AND r.status IN ('RECEIVED', 'IN_REVIEW')
WHERE a.status = 'FINISHED'
  AND a.payment_status = 'ESCROW_HELD'
ORDER BY reporter_role, a.updated_at;
