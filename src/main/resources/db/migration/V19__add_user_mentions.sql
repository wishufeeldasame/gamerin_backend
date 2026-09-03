CREATE TABLE IF NOT EXISTS user_mentions (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    post_id UUID REFERENCES posts(id) ON DELETE CASCADE,
    comment_id UUID REFERENCES post_comments(id) ON DELETE CASCADE,
    mentioned_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_mentions_post_user UNIQUE (post_id, mentioned_user_id),
    CONSTRAINT uq_user_mentions_comment_user UNIQUE (comment_id, mentioned_user_id),
    CONSTRAINT chk_user_mentions_source CHECK (
        (post_id IS NOT NULL AND comment_id IS NULL)
        OR (post_id IS NULL AND comment_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_user_mentions_mentioned_user_created_at_id
    ON user_mentions(mentioned_user_id, created_at DESC, id DESC);

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_source;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS mention_id UUID REFERENCES user_mentions(id) ON DELETE CASCADE;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_mention UNIQUE (mention_id),
    ADD CONSTRAINT chk_notifications_type CHECK (type IN (
        'LIKE',
        'COMMENT',
        'FOLLOW',
        'REPOST',
        'DIRECT_MESSAGE',
        'MENTORING_APPLICATION',
        'MENTORING_CANCELLED',
        'MENTORING_ACCEPTED',
        'MENTORING_REJECTED',
        'MENTORING_STARTED',
        'MENTORING_FINISHED',
        'MENTORING_COMPLETED',
        'MENTORING_REVIEW',
        'MENTION'
    )),
    ADD CONSTRAINT chk_notifications_source CHECK (
        (type = 'LIKE'
            AND post_id IS NOT NULL AND post_like_id IS NOT NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type = 'COMMENT'
            AND post_id IS NOT NULL AND post_like_id IS NULL
            AND comment_id IS NOT NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type = 'FOLLOW'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NOT NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type = 'REPOST'
            AND post_id IS NOT NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NOT NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type = 'DIRECT_MESSAGE'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NOT NULL AND message_id IS NOT NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type IN (
                'MENTORING_APPLICATION',
                'MENTORING_CANCELLED',
                'MENTORING_ACCEPTED',
                'MENTORING_REJECTED',
                'MENTORING_STARTED',
                'MENTORING_FINISHED',
                'MENTORING_COMPLETED'
            )
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NOT NULL AND mentoring_review_id IS NULL
            AND mention_id IS NULL)
        OR
        (type = 'MENTORING_REVIEW'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NOT NULL AND mentoring_review_id IS NOT NULL
            AND mention_id IS NULL)
        OR
        (type = 'MENTION'
            AND post_id IS NOT NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL
            AND mention_id IS NOT NULL)
    );
