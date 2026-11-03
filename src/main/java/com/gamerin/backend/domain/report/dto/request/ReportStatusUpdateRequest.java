package com.gamerin.backend.domain.report.dto.request;

import com.gamerin.backend.domain.report.entity.ReportStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 어드민이 신고 건의 처리 상태를 변경할 때 전송하는 요청 DTO
 * (현재 허용 값은 IN_REVIEW뿐이며, 종결 처리는 resolve API를 사용한다)
 */
public record ReportStatusUpdateRequest(
        @NotNull(message = "변경할 상태 값은 필수입니다.") ReportStatus status) {
}