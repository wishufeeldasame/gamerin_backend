package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;

public record MentoringApplicationResponse(
    UUID id,
    UUID programId,
    String programTitle,
    String mentorNickname,
    String menteeNickname,
    Long appliedMileage,
    ApplicationStatus status,
    PaymentStatus paymentStatus,
    String message,
    OffsetDateTime createdAt
) {
    public static MentoringApplicationResponse from(MentoringApplication application){
        return new MentoringApplicationResponse(
            application.getId(),
            application.getProgram().getId(),
            application.getProgram().getTitle(),
            application.getProgram().getMentor().getUser().getNickname(),
            application.getMentee().getNickname(),
            application.getAppliedMileage(),
            application.getStatus(),
            application.getPaymentStatus(),
            application.getMessage(),
            application.getCreatedAt()
        );
    }
}
