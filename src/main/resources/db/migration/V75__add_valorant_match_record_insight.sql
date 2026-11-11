-- 경기별 분석 근거(MatchInsight). 기존 행은 null로 두고 주간 집계에서 근거 없는 경기로 다룬다.
ALTER TABLE valorant_match_records ADD COLUMN insight JSONB;
