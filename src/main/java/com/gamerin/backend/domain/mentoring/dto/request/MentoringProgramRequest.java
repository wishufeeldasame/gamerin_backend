package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "멘토링 프로그램 등록 요청")
public record MentoringProgramRequest(
    @NotBlank(message = "게임 이름은 필수입니다.")
    @Schema(description = "게임 이름", example = "PUBG")
    String gameName,

    @NotBlank
    @Schema(description = "프로그램 제목", example = "상위 1%의 에임 교정 강의")
    String title,

    @NotBlank(message = "내용은 필수입니다.")
    @Schema(description = "상세 설명", example = "리플레이 분석을 통해 에임 습관을 고쳐드립니다.")
    String content,

    @Schema(description = "진행 가능 시간 설명", example = "평일 저녁 8시 이후 협의")
    String availableTimeDesc,

    @NotNull(message = "가격은 필수입니다.")
    @PositiveOrZero(message = "가격은 0원 이상이어야 합니다.")
    @Schema(description = "가격 (마일리지)", example = "10000")
    Long price,

    @Schema(description = "태그 목록", example = "[\"에임\", \"리플레이\", \"초보환영\"]")
    List<String> tags
) {    
}
