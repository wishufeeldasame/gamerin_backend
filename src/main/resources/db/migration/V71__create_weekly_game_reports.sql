-- 멤버십 주간 전적 리포트. week_start는 Asia/Seoul 기준 월요일이다.
CREATE TABLE weekly_game_reports (
    id UUID PRIMARY KEY,
    puuid VARCHAR(100) NOT NULL,
    game VARCHAR(20) NOT NULL,
    week_start DATE NOT NULL,
    stats JSONB,
    analysis JSONB,
    status VARCHAR(10) NOT NULL,
    ai_model VARCHAR(100),
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_weekly_game_reports_puuid_game_week UNIQUE (puuid, game, week_start),
    CONSTRAINT ck_weekly_game_reports_status CHECK (status IN ('READY', 'NO_DATA', 'FAILED'))
);
