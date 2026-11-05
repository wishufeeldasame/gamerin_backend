package com.gamerin.backend.domain.admin.dto.response;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.report.entity.ReportTargetType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 관리자가 수행한 제재, 콘텐츠 숨김/복구 등의 감사 로그
 * 이력을 반환하는 응답 DTO
 */
public record AdminAuditLogResponse(
        UUID id,
        UUID adminId,
        String adminNickname,
        String actionType,
        ReportTargetType targetType,
        UUID targetId,
        String requestId,
        String details,
        OffsetDateTime createdAt) {
    public static AdminAuditLogResponse from(AdminAuditLog log) {
        return new AdminAuditLogResponse(
                log.getId(),
                log.getAdmin().getId(),
                log.getAdmin().getNickname(),
                log.getActionType(),
                log.getTargetType(),
                log.getTargetId(),
                log.getRequestId(),
                log.getDetails(),
                log.getCreatedAt());
    }
}