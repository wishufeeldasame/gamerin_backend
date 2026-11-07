package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.gamerin.backend.domain.mentoring.entity.MentoringReview;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record MentoringReviewResponse(
    UUID id,
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID applicationId,
    UUID programId,
    String programTitle,
    String menteeNickname,
    int rating,
    String content,
    OffsetDateTime createdAt,
    boolean membershipBadge,
    RepresentativeTierResponse representativeTier
) {
    public static MentoringReviewResponse from(MentoringReview review, boolean membershipBadge) {
        return from(review, membershipBadge, true);
    }

    public static MentoringReviewResponse fromPublic(MentoringReview review, boolean membershipBadge) {
        return from(review, membershipBadge, false);
    }

    private static MentoringReviewResponse from(MentoringReview review, boolean membershipBadge, boolean includeApplication) {
        return new MentoringReviewResponse(
            review.getId(),
            includeApplication ? review.getApplication().getId() : null,
            review.getApplication().getProgram().getId(),
            review.getApplication().getProgram().getTitle(),
            review.getMentee().getNickname(),
            review.getRating(),
            review.getContent(),
            review.getCreatedAt(),
            membershipBadge,
            RepresentativeTierResponse.from(review.getMentee().getProfile())
        );
    }
} 
