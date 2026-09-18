package com.gamerin.backend.domain.report.dto.request;
    
import com.gamerin.backend.domain.report.entity.PenaltyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
    
import java.util.UUID;
    
/** 어드민이 유저에게 제재(경고, 7일/30일/영구 정지)를
부여할 때 전송하는 요청 DTO */
public record UserPenaltyCreateRequest(
    @NotNull(message = "제재 유형은 필수입니다.")
    PenaltyType penaltyType,
    
    @NotBlank(message = "제재 사유는 필수입니다.")
    String reason,
    
    Integer durationDays, // null이면 영구 정지(PERMANENT_BAN)
    
    UUID reportId // 관련된 신고 ID (선택)
) {}