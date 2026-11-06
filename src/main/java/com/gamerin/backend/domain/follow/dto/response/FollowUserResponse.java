package com.gamerin.backend.domain.follow.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record FollowUserResponse(
        UUID userId,
        String handle,
        String nickname,
        String bio,
        String profileImageUrl,
        boolean verifiedBadge,
        boolean membershipBadge,
        RepresentativeTierResponse representativeTier,
        boolean isFollowing,
        OffsetDateTime followedAt
) {
}
