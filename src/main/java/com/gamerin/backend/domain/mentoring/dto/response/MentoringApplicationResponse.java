package com.gamerin.backend.domain.mentoring.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.service.MentoringSettlementCalculator;

/**
 * 멘토링 신청 응답 DTO (KP-B2 배지, KP-C2 요율·수수료·수령액 표시)
 */
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean menteeMembershipBadge,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer feeRateBp,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long feeAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long payoutAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean nonMemberNotice,
        @JsonInclude(JsonInclude.Include.NON_NULL) String feeNotice) {
    public static final String NON_MEMBER_NOTICE_MESSAGE = "멤버십 가입 시 수수료 5% 혜택을 받을 수 있습니다.";

    /**
     * 어드민 엔드포인트용 팩토리 (배지 및 수수료 상세 미노출)
     */
    public static MentoringApplicationResponse from(MentoringApplication application) {
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
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 멘티 관점 응답 팩토리: 멘티 화면 가격은 그대로 유지하며 수수료/수령액 필드는
     * null로 제외
     */
    public static MentoringApplicationResponse fromMentee(
            MentoringApplication application,
            boolean reviewed,
            boolean mentorMembershipBadge,
            boolean menteeMembershipBadge) {
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
                menteeMembershipBadge,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * 멘토 관점 응답 팩토리: 요율·수수료·수령액 및 비가입 멘토 안내 정보 포함 (F5)
     */
    public static MentoringApplicationResponse fromMentor(
            MentoringApplication application,
            boolean reviewed,
            boolean mentorMembershipBadge,
            boolean menteeMembershipBadge) {
        int feeRateBp = application.getFeeRateBp() != null
                ? application.getFeeRateBp()
                : MentoringApplication.DEFAULT_FEE_RATE_BP;

        long feeAmount;
        long payoutAmount;
        if (application.getFeeAmount() != null) {
            feeAmount = application.getFeeAmount();
            payoutAmount = application.getAppliedMileage() != null
                    ? application.getAppliedMileage() - feeAmount
                    : 0L;
        } else if (application.getAppliedMileage() != null) {
            MentoringSettlementCalculator.SettlementResult result = MentoringSettlementCalculator
                    .calculate(application.getAppliedMileage(), feeRateBp);
            feeAmount = result.feeAmount();
            payoutAmount = result.payoutAmount();
        } else {
            feeAmount = 0L;
            payoutAmount = 0L;
        }

        boolean isNonMember = feeRateBp == MentoringApplication.DEFAULT_FEE_RATE_BP;
        Boolean nonMemberNotice = isNonMember ? Boolean.TRUE : null;
        String feeNotice = isNonMember ? NON_MEMBER_NOTICE_MESSAGE : null;

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
                menteeMembershipBadge,
                feeRateBp,
                feeAmount,
                payoutAmount,
                nonMemberNotice,
                feeNotice);
    }

    /**
     * 기존 코드 및 테스트와의 하위 호환성을 위한 오버로딩 팩토리 (기본 멘티 관점)
     */
    public static MentoringApplicationResponse from(MentoringApplication application,
            boolean reviewed,
            boolean mentorMembershipBadge, boolean menteeMembershipBadge) {
        return fromMentee(application, reviewed, mentorMembershipBadge,
                menteeMembershipBadge);
    }
}