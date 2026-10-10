package com.gamerin.backend.domain.report.dto.response;

import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportTargetType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 신고 누적 임계값 초과로 자동 숨김 처리된 콘텐츠 정보를
 * 어드민에게 반환하는 응답 DTO
 */
public record HiddenContentResponse(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        long reportCount,
        boolean isHidden,
        OffsetDateTime updatedAt) {
    public static HiddenContentResponse from(ReportCount count) {
        return new HiddenContentResponse(
                count.getId(),
                count.getTargetType(),
                count.getTargetId(),
                count.getReportCount(),
                count.isHidden(),
                count.getUpdatedAt());
    }
}