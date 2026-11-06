-- 게시물 조회수(#183). 같은 사용자의 같은 글 조회는 24시간에 1회만 센다.
ALTER TABLE posts ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;

-- (post_id, user_id)당 마지막으로 "센" 시각 1행. 24시간이 지난 재조회는 viewed_at만 갱신하므로 행이 늘지 않는다.
CREATE TABLE post_views (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);
