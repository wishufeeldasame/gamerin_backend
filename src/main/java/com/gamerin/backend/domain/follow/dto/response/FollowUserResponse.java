package com.gamerin.backend.domain.follow.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FollowUserResponse(
        UUID userId,
        String handle,
        String nickname,
        String bio,
        String profileImageUrl,
        boolean verifiedBadge,
        boolean membershipBadge,
        boolean isFollowing,
        OffsetDateTime followedAt
) {
}
