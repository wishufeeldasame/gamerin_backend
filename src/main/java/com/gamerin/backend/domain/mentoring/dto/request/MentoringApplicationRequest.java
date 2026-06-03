package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MentoringApplicationRequest(
    @NotNull(message = "신청할 프로그램 ID는 필수입니다.")
    UUID programId,

    @NotBlank(message = "멘토에게 전달할 메시지를 입력해주세요.")
    String message
) {
    
}
