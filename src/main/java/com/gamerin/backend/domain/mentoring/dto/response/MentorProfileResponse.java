package com.gamerin.backend.domain.mentoring.dto.response;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;
import java.util.UUID;

public record MentorProfileResponse(
    UUID userId,
    String nickname,
    MentorStatus status,
    String about,
    double ratingAvg,
    int reviewCount,
    int menteeCount,
    boolean membershipBadge,
    RepresentativeTierResponse representativeTier
){
    public static MentorProfileResponse from (MentorProfile profile, boolean membershipBadge) {
        return new MentorProfileResponse(
            profile.getUserId(),
            profile.getUser().getNickname(),
            profile.getStatus(),
            profile.getAbout(),
            profile.getRatingAvg().doubleValue(),
            profile.getReviewCount(),
            profile.getMenteeCount(),
            membershipBadge,
            RepresentativeTierResponse.from(profile.getUser().getProfile())
        );
    }
}
