package com.gamerin.backend.domain.report.dto.request;

import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 어드민 신고 원클릭 통합 판정 요청 DTO
 */
public record AdminReportResolutionRequest(
        @NotNull(message = "처리 결정(RESOLVED 또는 REJECTED)은 필수입니다.") ReportStatus decision,

        boolean hideTargetContent,

        PenaltyType penaltyType, // null이면 제재 없음

        @NotBlank(message = "처리 사유는 필수입니다.") String reason,

        String internalMemo,

        Boolean includeRelatedReports) {
}