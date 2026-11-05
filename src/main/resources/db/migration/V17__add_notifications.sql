CREATE TABLE IF NOT EXISTS notifications (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    post_id UUID REFERENCES posts(id) ON DELETE CASCADE,
    post_like_id UUID REFERENCES post_likes(id) ON DELETE CASCADE,
    comment_id UUID REFERENCES post_comments(id) ON DELETE CASCADE,
    follow_id UUID REFERENCES follows(id) ON DELETE CASCADE,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notifications_post_like UNIQUE (post_like_id),
    CONSTRAINT uq_notifications_comment UNIQUE (comment_id),
    CONSTRAINT uq_notifications_follow UNIQUE (follow_id),
    CONSTRAINT chk_notifications_not_self CHECK (recipient_id <> actor_id),
    CONSTRAINT chk_notifications_type CHECK (type IN ('LIKE', 'COMMENT', 'FOLLOW')),
    CONSTRAINT chk_notifications_source CHECK (
        (type = 'LIKE'
            AND post_id IS NOT NULL
            AND post_like_id IS NOT NULL
            AND comment_id IS NULL
            AND follow_id IS NULL)
        OR
        (type = 'COMMENT'
            AND post_id IS NOT NULL
            AND post_like_id IS NULL
            AND comment_id IS NOT NULL
            AND follow_id IS NULL)
        OR
        (type = 'FOLLOW'
            AND post_id IS NULL
            AND post_like_id IS NULL
            AND comment_id IS NULL
            AND follow_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created_at_id
    ON notifications(recipient_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread_created_at_id
    ON notifications(recipient_id, created_at DESC, id DESC)
    WHERE read_at IS NULL;
