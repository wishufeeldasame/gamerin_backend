package com.gamerin.backend.domain.user.dto.request;

import com.gamerin.backend.domain.user.entity.Theme;

import jakarta.validation.constraints.NotNull;

/** 테마 설정 변경 요청. null은 "아직 고르지 않음"이므로 변경 요청에서는 허용하지 않는다. */
public record UpdateUserSettingsRequest(
        @NotNull(message = "테마를 선택해 주세요.")
        Theme theme
) {
}
