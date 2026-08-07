package com.gamerin.backend.domain.admin.dto.response;

import com.gamerin.backend.domain.admin.entity.SystemConfig;

import java.time.OffsetDateTime;

/**
 * 시스템 설정 항목의 키, 값, 설명, 수정 일시 정보를
 * 반환하는 응답 DTO
 */
public record SystemConfigResponse(
        String configKey,
        String configValue,
        String description,
        OffsetDateTime updatedAt) {
    public static SystemConfigResponse from(SystemConfig config) {
        return new SystemConfigResponse(
                config.getConfigKey(),
                config.getConfigValue(),
                config.getDescription(),
                config.getUpdatedAt());
    }
}