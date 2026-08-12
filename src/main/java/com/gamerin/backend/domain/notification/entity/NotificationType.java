package com.gamerin.backend.domain.notification.entity;

import java.util.Locale;

public enum NotificationType {
    LIKE,
    COMMENT,
    FOLLOW;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
