package com.gamerin.backend.domain.notification.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        String type,
        NotificationActorResponse actor,
        UUID postId,
        UUID commentId,
        boolean read,
        OffsetDateTime createdAt
) {
}
