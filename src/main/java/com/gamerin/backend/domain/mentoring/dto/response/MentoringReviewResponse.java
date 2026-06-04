package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.mentoring.entity.MentoringReview;

public record MentoringReviewResponse(
    UUID id,
    UUID applicationId,
    UUID programId,
    String programTitle,
    String menteeNickname,
    int rating,
    String content,
    OffsetDateTime createdAt
) {
    public static MentoringReviewResponse from(MentoringReview review) {
        return new MentoringReviewResponse(
            review.getId(),
            review.getApplication().getId(),
            review.getApplication().getProgram().getId(),
            review.getApplication().getProgram().getTitle(),
            review.getMentee().getNickname(),
            review.getRating(),
            review.getContent(),
            review.getCreatedAt()
        );
    }
} 
