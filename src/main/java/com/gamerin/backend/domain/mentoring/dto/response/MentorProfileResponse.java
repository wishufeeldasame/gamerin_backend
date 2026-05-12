package com.gamerin.backend.domain.mentoring.dto.response;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import java.util.UUID;

public record MentorProfileResponse(
    UUID userId,
    String nickname,
    MentorStatus status,
    String about,
    double ratingAvg,
    int reviewCount,
    int menteeCount
){
    public static MentorProfileResponse from (MentorProfile profile) {
        return new MentorProfileResponse(
            profile.getUserId(),
            profile.getUser().getNickname(),
            profile.getStatus(),
            profile.getAbout(),
            profile.getRatingAvg().doubleValue(),
            profile.getReviewCount(),
            profile.getMenteeCount()
        );
    }
}