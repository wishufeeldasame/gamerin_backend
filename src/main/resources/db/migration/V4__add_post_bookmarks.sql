CREATE TABLE IF NOT EXISTS post_bookmarks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (post_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_post_bookmarks_user_created
    ON post_bookmarks (user_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_post_bookmarks_post_user
    ON post_bookmarks (post_id, user_id);
