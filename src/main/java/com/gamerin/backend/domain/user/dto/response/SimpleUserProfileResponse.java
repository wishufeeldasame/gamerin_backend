package com.gamerin.backend.domain.user.dto.response;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import java.util.UUID;

public record SimpleUserProfileResponse(
        UUID id,
        String handle,
        String nickname,
        String profileImageUrl,
        boolean verifiedBadge
) {
    public static SimpleUserProfileResponse from(User user) {
        UserProfile profile = user.getProfile();
        return new SimpleUserProfileResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge()
        );
    }
}