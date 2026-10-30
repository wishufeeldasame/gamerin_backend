-- 멘토링 프로그램 소프트 삭제 지원
    -- deleted_at이 NULL이면 정상 운영 중, NOT NULL이면 멘토가 삭제 처리한 프로그램
    ALTER TABLE mentoring_programs
        ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;