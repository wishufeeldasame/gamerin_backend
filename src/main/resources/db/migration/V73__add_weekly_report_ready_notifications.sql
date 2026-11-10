-- 주간 리포트 완료 알림. 회원·리포트마다 한 번만 생성한다.
ALTER TABLE notifications
    ADD COLUMN weekly_report_id UUID REFERENCES weekly_game_reports(id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_notifications_recipient_weekly_report UNIQUE (recipient_id, weekly_report_id);

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_actor;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_source;

ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_actor CHECK (
        actor_id IS NOT NULL OR type IN ('MENTORING_COMPLETED', 'MENTORING_REFUNDED', 'WEEKLY_REPORT_READY')
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
        'MENTION',
        'WEEKLY_REPORT_READY'
    )),
    ADD CONSTRAINT chk_notifications_source CHECK (
        (weekly_report_id IS NULL AND (
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
        )) OR (
            type = 'WEEKLY_REPORT_READY' AND weekly_report_id IS NOT NULL AND actor_id IS NULL
            AND post_id IS NULL AND post_like_id IS NULL AND comment_id IS NULL
            AND follow_id IS NULL AND post_repost_id IS NULL AND conversation_id IS NULL
            AND message_id IS NULL AND mentoring_application_id IS NULL
            AND mentoring_review_id IS NULL AND mention_id IS NULL
        )
    );
