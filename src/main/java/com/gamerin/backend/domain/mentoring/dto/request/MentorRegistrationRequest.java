package com.gamerin.backend.domain.mentoring.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "멘토 등록 요청")
public record MentorRegistrationRequest(
    @NotBlank(message = "멘토 등록 요청")
    @Schema(description = "멘토 소개", example = "안녕하세요, 배틀그라운드 멘토입니다.")
    String about
) {  
}
