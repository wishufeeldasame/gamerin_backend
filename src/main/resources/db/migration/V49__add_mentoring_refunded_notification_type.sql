-- 어드민 강제 환불 알림(MENTORING_REFUNDED) 도입
-- MENTORING_COMPLETED와 동일하게 시스템/관리자 조치이므로 actor_id가 NULL이어도 허용한다.

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_actor;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_source;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_actor CHECK (
        actor_id IS NOT NULL OR type IN ('MENTORING_COMPLETED', 'MENTORING_REFUNDED')
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
        'MENTORING_REFUNDED',
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
                'MENTORING_COMPLETED',
                'MENTORING_REFUNDED'
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
