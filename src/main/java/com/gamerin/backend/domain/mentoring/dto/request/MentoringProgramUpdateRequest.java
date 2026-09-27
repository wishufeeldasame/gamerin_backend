package com.gamerin.backend.domain.mentoring.dto.request;

import java.util.List;

import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "멘토링 프로그램 수정 요청")
public record MentoringProgramUpdateRequest(
    @NotBlank(message = "제목은 필수입니다.")
    @Schema(description = "프로그램 제목", example = "상위 1%의 에임 교정 강의 (수정)")
    String title,

    @NotBlank(message = "내용은 필수입니다.")
    @Schema(description = "상세 설명", example = "수정된 강의 커리큘럼입니다.")
    String content,

    @Schema(description = "진행 가능 시간 설명", example = "평일 저녁 8시 이후 협의")
    String availableTimeDesc,

    @NotNull(message = "가격은 필수입니다.")
    @PositiveOrZero(message = "가격은 0원 이상이어야 합니다.")
    @Schema(description = "가격 (마일리지)", example = "10000")
    Long price,

    @NotNull(message = "상태는 필수입니다.")
    @Schema(description = "프로그램 상태", example = "ACTIVE")
    ProgramStatus status, // 활성/비활성 상태 변경 가능

    @Schema(description = "태그 목록", example = "[\"에임\", \"리플레이\", \"초보환영\"]")
    List<String> tags
) {
}