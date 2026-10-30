package com.gamerin.backend.domain.admin.dto.response;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 멘토 심사/관리 목록 응답 DTO
 */
public record AdminMentorResponse(
        UUID userId,
        String name,
        String handle,
        String bio,
        BigDecimal ratingAvg,
        int reviewCount,
        int menteeCount,
        MentorStatus status,
        OffsetDateTime appliedAt) {
    public static AdminMentorResponse from(MentorProfile profile) {
        return new AdminMentorResponse(
                profile.getUserId(),
                profile.getUser().getNickname(),
                profile.getUser().getHandle(),
                profile.getAbout(),
                profile.getRatingAvg(),
                profile.getReviewCount(),
                profile.getMenteeCount(),
                profile.getStatus(),
                profile.getCreatedAt());
    }
}