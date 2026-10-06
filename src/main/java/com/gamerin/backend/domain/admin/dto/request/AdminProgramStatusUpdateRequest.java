package com.gamerin.backend.domain.admin.dto.request;

import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 어드민 프로그램 상태(ACTIVE/CLOSED) 변경 요청 DTO
 */
public record AdminProgramStatusUpdateRequest(
        @NotNull(message = "프로그램 상태는 필수입니다.") ProgramStatus status) {
}