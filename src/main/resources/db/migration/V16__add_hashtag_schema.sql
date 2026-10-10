CREATE TABLE IF NOT EXISTS hashtags (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL,
    normalized_name VARCHAR(150) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_hashtag_display_name_length
        CHECK (char_length(display_name) BETWEEN 1 AND 50),
    CONSTRAINT uq_hashtag_normalized_name
        UNIQUE (normalized_name)
);

CREATE TABLE IF NOT EXISTS post_hashtags (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_hashtag
        UNIQUE (post_id, hashtag_id)
);

CREATE INDEX IF NOT EXISTS idx_post_hashtags_hashtag_post
    ON post_hashtags (hashtag_id, post_id);

CREATE INDEX IF NOT EXISTS idx_post_hashtags_post
    ON post_hashtags (post_id);
