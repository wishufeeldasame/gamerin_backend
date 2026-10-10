package com.gamerin.backend.domain.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 어드민이 시스템 설정 항목의 값(value)을 수정할 때
 * 전송하는 요청 DTO
 */
public record SystemConfigUpdateRequest(
        @NotBlank(message = "설정값은 필수입니다.") String configValue) {
}