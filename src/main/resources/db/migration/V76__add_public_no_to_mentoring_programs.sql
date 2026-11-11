-- 멘토링 프로그램 URL용 짧은 숫자 번호 (예: /mentoring/programs/12). PK(UUID)는 그대로 유지한다.
CREATE SEQUENCE IF NOT EXISTS mentoring_programs_public_no_seq;

ALTER TABLE mentoring_programs
    ADD COLUMN IF NOT EXISTS public_no BIGINT;

UPDATE mentoring_programs p
SET public_no = n.rn
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS rn FROM mentoring_programs) n
WHERE p.id = n.id AND p.public_no IS NULL;

SELECT setval('mentoring_programs_public_no_seq', COALESCE((SELECT MAX(public_no) FROM mentoring_programs), 0) + 1, false);

ALTER TABLE mentoring_programs
    ALTER COLUMN public_no SET DEFAULT nextval('mentoring_programs_public_no_seq'),
    ALTER COLUMN public_no SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mentoring_programs_public_no ON mentoring_programs(public_no);