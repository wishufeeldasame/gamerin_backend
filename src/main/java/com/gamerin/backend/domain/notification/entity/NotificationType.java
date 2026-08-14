package com.gamerin.backend.domain.notification.entity;

import java.util.Locale;

public enum NotificationType {
    LIKE,
    COMMENT,
    FOLLOW,
    REPOST,
    DIRECT_MESSAGE,
    MENTORING_APPLICATION,
    MENTORING_CANCELLED,
    MENTORING_ACCEPTED,
    MENTORING_REJECTED,
    MENTORING_STARTED,
    MENTORING_FINISHED,
    MENTORING_COMPLETED,
    MENTORING_REVIEW,
    MENTION;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
