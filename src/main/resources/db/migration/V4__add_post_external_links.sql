CREATE TABLE IF NOT EXISTS post_external_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    original_url TEXT NOT NULL,
    host VARCHAR(255) NOT NULL,
    title VARCHAR(255),
    description TEXT,
    thumbnail_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_external_links_post UNIQUE (post_id)
);

CREATE INDEX IF NOT EXISTS idx_post_external_links_post_id
    ON post_external_links(post_id);
