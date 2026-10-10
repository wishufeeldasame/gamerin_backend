ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_source;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_not_self;

ALTER TABLE notifications
    ALTER COLUMN actor_id DROP NOT NULL,
    ALTER COLUMN type TYPE VARCHAR(40),
    ADD COLUMN IF NOT EXISTS post_repost_id UUID REFERENCES post_reposts(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES message_conversations(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS message_id UUID REFERENCES direct_messages(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS mentoring_application_id UUID REFERENCES mentoring_applications(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS mentoring_review_id UUID REFERENCES mentoring_reviews(id) ON DELETE CASCADE;

ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS event_at TIMESTAMP WITH TIME ZONE;

UPDATE notifications
SET event_at = created_at
WHERE event_at IS NULL;

ALTER TABLE notifications
    ALTER COLUMN event_at SET NOT NULL,
    ALTER COLUMN event_at SET DEFAULT NOW();

DROP INDEX IF EXISTS idx_notifications_recipient_created_at_id;
DROP INDEX IF EXISTS idx_notifications_recipient_unread_created_at_id;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_post_repost UNIQUE (post_repost_id),
    ADD CONSTRAINT uq_notifications_recipient_conversation UNIQUE (recipient_id, conversation_id),
    ADD CONSTRAINT uq_notifications_recipient_mentoring_event
        UNIQUE (recipient_id, type, mentoring_application_id),
    ADD CONSTRAINT uq_notifications_mentoring_review UNIQUE (mentoring_review_id),
    ADD CONSTRAINT chk_notifications_not_self CHECK (actor_id IS NULL OR recipient_id <> actor_id),
    ADD CONSTRAINT chk_notifications_actor CHECK (
        actor_id IS NOT NULL OR type = 'MENTORING_COMPLETED'
    ),
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
        'MENTORING_REVIEW'
    )),
    ADD CONSTRAINT chk_notifications_source CHECK (
        (type = 'LIKE'
            AND post_id IS NOT NULL AND post_like_id IS NOT NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL)
        OR
        (type = 'COMMENT'
            AND post_id IS NOT NULL AND post_like_id IS NULL
            AND comment_id IS NOT NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL)
        OR
        (type = 'FOLLOW'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NOT NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL)
        OR
        (type = 'REPOST'
            AND post_id IS NOT NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NOT NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL)
        OR
        (type = 'DIRECT_MESSAGE'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NOT NULL AND message_id IS NOT NULL
            AND mentoring_application_id IS NULL AND mentoring_review_id IS NULL)
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
            AND mentoring_application_id IS NOT NULL AND mentoring_review_id IS NULL)
        OR
        (type = 'MENTORING_REVIEW'
            AND post_id IS NULL AND post_like_id IS NULL
            AND comment_id IS NULL AND follow_id IS NULL AND post_repost_id IS NULL
            AND conversation_id IS NULL AND message_id IS NULL
            AND mentoring_application_id IS NOT NULL AND mentoring_review_id IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_notifications_mentoring_application
    ON notifications(mentoring_application_id)
    WHERE mentoring_application_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_event_at_id
    ON notifications(recipient_id, event_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread_event_at_id
    ON notifications(recipient_id, event_at DESC, id DESC)
    WHERE read_at IS NULL;
