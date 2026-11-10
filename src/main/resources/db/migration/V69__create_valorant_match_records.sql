-- 발로란트 경쟁전 경기 단위 기록. 주간 리포트 집계용으로 puuid 기준 누적한다.
CREATE TABLE valorant_match_records (
    id UUID PRIMARY KEY,
    puuid VARCHAR(100) NOT NULL,
    match_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    map_name VARCHAR(50),
    agent_name VARCHAR(50),
    tier_id INT,
    tier_label VARCHAR(30),
    kills INT NOT NULL,
    deaths INT NOT NULL,
    assists INT NOT NULL,
    score INT NOT NULL,
    rounds INT NOT NULL,
    headshots INT NOT NULL,
    bodyshots INT NOT NULL,
    legshots INT NOT NULL,
    won BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_valorant_match_records_puuid_match UNIQUE (puuid, match_id)
);
CREATE INDEX idx_valorant_match_records_puuid_started_at ON valorant_match_records(puuid, started_at);
