package com.gamerin.backend.domain.user.dto.response;

import java.util.Map;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserRecommendationResponse(
        UUID userId,
        String handle,
        String nickname,
        @Schema(nullable = true) String profileImageUrl,
        boolean membershipBadge,
        @Schema(description = "내가 팔로우하는 활성 사용자 중 이 사용자를 팔로우하는 수. 보충 추천은 0") long mutualCount,
        @Schema(description = "대표 게임 티어. 게임/연동/티어가 없으면 null") RepresentativeTierResponse representativeTier,
        @Schema(description = "대표 게임의 마지막 저장 승률(0~100). 게임/티어/승률이 없으면 null", minimum = "0", maximum = "100", nullable = true) Double winRate
) {
    public static UserRecommendationResponse from(User user, long mutualCount, boolean membershipBadge) {
        UserProfile profile = user.getProfile();
        RepresentativeTierResponse tier = RepresentativeTierResponse.from(profile);
        return new UserRecommendationResponse(
                user.getId(), user.getHandle(), user.getNickname(),
                profile == null ? null : profile.getProfileImageUrl(),
                membershipBadge, mutualCount, tier, winRate(profile, tier));
    }

    private static Double winRate(UserProfile profile, RepresentativeTierResponse tier) {
        if (tier == null || !(profile.getGameStats().get(tier.game().name()) instanceof Map<?, ?> stats)
                || !(stats.get("winRate") instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= 0 && value <= 100 ? value : null;
    }
}
