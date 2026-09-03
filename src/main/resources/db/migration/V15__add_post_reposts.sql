CREATE TABLE IF NOT EXISTS post_reposts (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reposted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_reposts_post_user UNIQUE (post_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_post_reposts_user_reposted_at_id
    ON post_reposts(user_id, reposted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_post_reposts_post_reposted_at_id
    ON post_reposts(post_id, reposted_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_post_reposts_reposted_at_id
    ON post_reposts(reposted_at DESC, id DESC);
