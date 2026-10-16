package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;

public record MentoringApplicationResponse(
    UUID id,
    UUID programId,
    String programTitle,
    UUID mentorId,
    UUID menteeId,
    String mentorNickname,
    String menteeNickname,
    Long appliedMileage,
    ApplicationStatus status,
    PaymentStatus paymentStatus,
    String message,
    OffsetDateTime createdAt,
    boolean reviewed,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean mentorMembershipBadge,
    @JsonInclude(JsonInclude.Include.NON_NULL) Boolean menteeMembershipBadge
) {
    public static MentoringApplicationResponse from(MentoringApplication application){
        // Admin endpoints share this response but do not expose membership badges.
        return create(application, false, null, null);
    }

    public static MentoringApplicationResponse from(MentoringApplication application, boolean reviewed,
            boolean mentorMembershipBadge, boolean menteeMembershipBadge){
        return create(application, reviewed, mentorMembershipBadge, menteeMembershipBadge);
    }

    private static MentoringApplicationResponse create(MentoringApplication application, boolean reviewed,
            Boolean mentorMembershipBadge, Boolean menteeMembershipBadge){
        return new MentoringApplicationResponse(
            application.getId(),
            application.getProgram().getId(),
            application.getProgram().getTitle(),
            application.getProgram().getMentor().getId(),
            application.getMentee().getId(),
            application.getProgram().getMentor().getUser().getNickname(),
            application.getMentee().getNickname(),
            application.getAppliedMileage(),
            application.getStatus(),
            application.getPaymentStatus(),
            application.getMessage(),
            application.getCreatedAt(),
            reviewed,
            mentorMembershipBadge,
            menteeMembershipBadge
        );
    }
}
