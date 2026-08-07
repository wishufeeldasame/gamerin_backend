package com.gamerin.backend.domain.report.dto.request;

import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// 일반 유저 신고 접수 요청
public record ReportCreateRequest(
        @NotNull(message = "신고 대상 유형은 필수입니다.") ReportTargetType targetType,

        @NotNull(message = "신고 대상 ID는 필수입니다.") UUID targetId,

        @NotNull(message = "신고 사유 코드는 필수입니다.") ReportReasonCode reasonCode,

        String details) {
}