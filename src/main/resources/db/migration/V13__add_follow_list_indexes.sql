CREATE INDEX IF NOT EXISTS idx_follows_followee_created_at_id
    ON follows(followee_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_follows_follower_created_at_id
    ON follows(follower_id, created_at DESC, id DESC);
