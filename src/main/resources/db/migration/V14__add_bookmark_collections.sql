CREATE TABLE IF NOT EXISTS bookmark_collections (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_bookmark_collection_name_length
        CHECK (char_length(name) BETWEEN 1 AND 40),
    CONSTRAINT uq_bookmark_collection_user_name
        UNIQUE (user_id, normalized_name)
);

CREATE INDEX IF NOT EXISTS idx_bookmark_collections_user_created
    ON bookmark_collections (user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS bookmark_collection_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES bookmark_collections(id) ON DELETE CASCADE,
    post_bookmark_id UUID NOT NULL REFERENCES post_bookmarks(id) ON DELETE CASCADE,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_bookmark_collection_item
        UNIQUE (collection_id, post_bookmark_id)
);

CREATE INDEX IF NOT EXISTS idx_bookmark_collection_items_collection_added
    ON bookmark_collection_items (collection_id, added_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_bookmark_collection_items_post_bookmark
    ON bookmark_collection_items (post_bookmark_id);
