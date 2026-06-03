package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MentoringReviewRequest(
    @NotNull(message = "신청 ID는 필수입니다.")
    UUID applicationId,

    @Min(value = 1, message = "평점은 최소 1점 이상이어야 합니다.")
    @Max(value = 5, message = "평점은 최대 5점 이하여야 합니다.")
    int rating,

    @NotBlank(message = "리뷰 내용을 입력해주세요.")
    String content
) {

}
