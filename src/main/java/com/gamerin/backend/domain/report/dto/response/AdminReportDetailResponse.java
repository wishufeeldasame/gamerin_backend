package com.gamerin.backend.domain.report.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 프론트엔드 AdminReportDetail 화면용 통합 응답 DTO
 * (신고 정보 + 신고자 요약 + 피신고자 요약 + 콘텐츠 숨김 여부)
 */
public record AdminReportDetailResponse(
        ReportResponse report,
        ReportUserSummary reporter,
        ReportUserSummary targetUser,
        boolean contentHidden) {
    public record ReportUserSummary(
            UUID id,
            String nickname,
            String handle,
            OffsetDateTime joinedAt,
            long reportsReceived,
            String activeSanction) {
    }
}