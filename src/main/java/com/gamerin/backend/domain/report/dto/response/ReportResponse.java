package com.gamerin.backend.domain.report.dto.response;

import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 신고 접수 완료 결과 및 어드민 신고 목록/상세 조회 시
 * 제공되는 신고 정보 응답 DTO
 */
public record ReportResponse(
        UUID id,
        String reportCode,
        UUID reporterId,
        String reporterNickname,
        ReportTargetType targetType,
        UUID targetId,
        String targetSnippet,
        ReportReasonCode reasonCode,
        String reasonLabel,
        String details,
        ReportStatus status,
        UUID assignedAdminId,
        String assignedAdminNickname,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
    public static ReportResponse from(Report report) {
            return new ReportResponse(
                    report.getId(),
                    report.getReportCode(),
                    report.getReporter().getId(),
                    report.getReporter().getNickname(),
                    report.getTargetType(),
                    report.getTargetId(),
                    report.getTargetSnippet(),
                    report.getReasonCode(),
                    report.getReasonCode().getDescription(), // reasonCode.getDescription() 사용
                    report.getDetails(),
                    report.getStatus(),
                    report.getAssignedAdmin() != null ? report.
  getAssignedAdmin().getId() : null,
                    report.getAssignedAdmin() != null ? report.
  getAssignedAdmin().getNickname() : null,
                    report.getCreatedAt(),
                    report.getUpdatedAt()
            );
        }
}