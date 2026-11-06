package com.gamerin.backend.domain.notification.dto.response;

import java.util.UUID;

import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record NotificationActorResponse(
        UUID userId,
        String handle,
        String nickname,
        String profileImageUrl,
        boolean verifiedBadge,
        boolean membershipBadge,
        RepresentativeTierResponse representativeTier
) {
}
