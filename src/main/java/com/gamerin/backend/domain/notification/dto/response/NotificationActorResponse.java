package com.gamerin.backend.domain.notification.dto.response;

import java.util.UUID;

public record NotificationActorResponse(
        UUID userId,
        String handle,
        String nickname,
        String profileImageUrl,
        boolean verifiedBadge
) {
}
