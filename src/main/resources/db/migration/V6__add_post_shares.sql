CREATE TABLE IF NOT EXISTS post_shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    share_target VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_post_shares_post_created
    ON post_shares (post_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_post_shares_user_created
    ON post_shares (user_id, created_at DESC);
