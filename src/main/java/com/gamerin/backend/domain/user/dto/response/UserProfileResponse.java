package com.gamerin.backend.domain.user.dto.response;

import java.util.Map;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String handle,
        String nickname,
        String bio,
        String profileImageUrl,
        Map<String, Object> gameStats,
        boolean verifiedBadge,
        boolean isFollowing,
        long followersCount,
        long followingCount,
        long postCount,
        long mediaPostCount,
        long mediaItemCount
) {
}
