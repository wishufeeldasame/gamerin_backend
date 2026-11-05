-- 게시물 영상 비동기 처리 작업. 재인코딩이 끝나면 워커가 게시물을 만들고 post_id를 채운다.
CREATE TABLE post_video_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT,
    quality_tier VARCHAR(10) NOT NULL,
    input_path TEXT NOT NULL,
    input_extension VARCHAR(10) NOT NULL,
    thumbnail_url TEXT,
    status VARCHAR(20) NOT NULL,
    post_id UUID REFERENCES posts(id) ON DELETE SET NULL,
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_post_video_jobs_status_created_at
    ON post_video_jobs (status, created_at);
