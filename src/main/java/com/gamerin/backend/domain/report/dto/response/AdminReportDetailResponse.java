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
        boolean contentHidden,
        EscrowResult escrowResult) {

    /** 신청 건 신고가 아니거나 에스크로 조치와 무관한 경우 escrowResult는 null. */
    public AdminReportDetailResponse(
            ReportResponse report,
            ReportUserSummary reporter,
            ReportUserSummary targetUser,
            boolean contentHidden) {
        this(report, reporter, targetUser, contentHidden, null);
    }

    public AdminReportDetailResponse withEscrowResult(EscrowResult result) {
        return new AdminReportDetailResponse(report, reporter, targetUser, contentHidden, result);
    }

    /** 멘토링 신청 건 신고 판정 시 환불/정산이 실제로 수행되었는지 여부 */
    public enum EscrowOutcome {
        REFUNDED,
        SETTLED,
        NOT_PERFORMED
    }

    public record EscrowResult(EscrowOutcome outcome, String note) {
        public static EscrowResult refunded() {
            return new EscrowResult(EscrowOutcome.REFUNDED, "멘티에게 강제 환불되었습니다.");
        }

        public static EscrowResult settled() {
            return new EscrowResult(EscrowOutcome.SETTLED, "멘토에게 강제 정산되었습니다.");
        }

        public static EscrowResult notPerformed(String note) {
            return new EscrowResult(EscrowOutcome.NOT_PERFORMED, note);
        }

        /** 감사 로그에 덧붙일 문구 */
        public String auditSuffix() {
            return " [에스크로 조치: " + outcome + (outcome == EscrowOutcome.NOT_PERFORMED ? " - " + note : "") + "]";
        }
    }

    public record ReportUserSummary(
            UUID id,
            String nickname,
            String handle,
            OffsetDateTime joinedAt,
            long reportsReceived,
            String activeSanction) {
    }
}