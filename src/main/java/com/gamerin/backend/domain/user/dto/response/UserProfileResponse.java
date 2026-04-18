package com.gamerin.backend.domain.user.dto.response;

import java.util.Map;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;

public record UserProfileResponse(
    UUID id,
    String handle,
    String nickname,
    String bio,
    String profileImageUrl,
    Map<String, Object> gameStats,
    boolean verifiedBadge
) {
    // 엔티티를 DTO로 변환하는 정적 메서드
    public static UserProfileResponse from(User user) {
        UserProfile profile = user.getProfile();
        return new UserProfileResponse(
            user.getId(),
            user.getHandle(),
            user.getNickname(),
            profile != null ? profile.getBio() : null,
            profile != null ? profile.getProfileImageUrl() : null,
            profile != null ? profile.getGameStats() : null,
            profile != null && profile.isVerifiedBadge()
        );
    }
} 
