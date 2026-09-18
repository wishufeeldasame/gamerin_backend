package com.gamerin.backend.domain.report.dto.request;

import com.gamerin.backend.domain.report.entity.ReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 어드민이 신고 건의 처리 상태(RECEIVED, IN_REVIEW,
 * RESOLVED, REJECTED)를 변경할 때 전송하는 요청 DTO
 */
public record ReportStatusUpdateRequest(
        @NotNull(message = "변경할 상태 값은 필수입니다.") ReportStatus status) {
}