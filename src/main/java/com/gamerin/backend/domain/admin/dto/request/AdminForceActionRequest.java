package com.gamerin.backend.domain.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 관리자 에스크로 강제 개입(환불/정산) 조치 사유 요청 DTO
 */
@Schema(description = "관리자 에스크로 강제 개입 요청")
public record AdminForceActionRequest(
        @Schema(description = "강제 조치 사유", example = "멘토 노쇼 분쟁 조정에 따른 멘티 전액 강제 환불")
        @NotBlank(message = "조치 사유는 필수 입력 항목입니다.")
        String reason
) {
}