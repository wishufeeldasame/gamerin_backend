package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.List;

import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MentoringProgramUpdateRequest(
    @NotBlank String title,
    @NotBlank String content,
    String availableTimeDesc,
    @NotNull Long price,
    @NotNull ProgramStatus status, // 활성/비활성 상태 변경 가능
    List<String> tags
) {
    
}
