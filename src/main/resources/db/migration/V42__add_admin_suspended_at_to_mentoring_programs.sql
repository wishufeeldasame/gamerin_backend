-- 멘토링 프로그램 관리자 일시정지 지원
-- admin_suspended_at이 NULL이면 정상 운영 또는 멘토 마감, NOT NULL이면 관리자가 일시정지(CLOSED)한 시각
ALTER TABLE mentoring_programs
    ADD COLUMN IF NOT EXISTS admin_suspended_at TIMESTAMPTZ;