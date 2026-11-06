-- 게시물 조회수(#183). 같은 사용자의 같은 글 조회는 24시간에 1회만 센다.
ALTER TABLE posts ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;

-- (post_id, user_id)당 마지막으로 "센" 시각 1행. 24시간이 지난 재조회는 viewed_at만 갱신하므로 행이 늘지 않는다.
CREATE TABLE post_views (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

-- 회원 탈퇴 시 user_id로 열람 이력을 지운다(PK는 post_id가 앞이라 user_id 조회를 지원하지 않는다).
CREATE INDEX idx_post_views_user ON post_views(user_id);
