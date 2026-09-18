package com.gamerin.backend.domain.report.dto.response;

import com.gamerin.backend.domain.report.entity.PenaltyType;
import com.gamerin.backend.domain.report.entity.UserPenalty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 유저에게 부여된 제재(경고, 정지) 이력 상세 정보를
 * 반환하는 응답 DTO
 */
public record UserPenaltyResponse(
        UUID id,
        UUID userId,
        String userNickname,
        UUID reportId,
        PenaltyType penaltyType,
        String reason,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        boolean isActive,
        UUID administeredByAdminId,
        String administeredByAdminNickname,
        OffsetDateTime createdAt) {
    public static UserPenaltyResponse from(UserPenalty penalty) {
        return new UserPenaltyResponse(
                penalty.getId(),
                penalty.getUser().getId(),
                penalty.getUser().getNickname(),
                penalty.getReport() != null ? penalty.getReport().getId() : null,
                penalty.getPenaltyType(),
                penalty.getReason(),
                penalty.getStartAt(),
                penalty.getEndAt(),
                penalty.isActive(),
                penalty.getAdministeredBy() != null ? penalty.getAdministeredBy().getId() : null,
                penalty.getAdministeredBy() != null ? penalty.getAdministeredBy().getNickname() : null,
                penalty.getCreatedAt());
    }
}