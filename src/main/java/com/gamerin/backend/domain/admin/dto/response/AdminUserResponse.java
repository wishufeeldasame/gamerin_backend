package com.gamerin.backend.domain.admin.dto.response;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.user.entity.UserStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 어드민 사용자 관리 목록 및 상세 조회용 통합 응답 DTO
 */
public record AdminUserResponse(
        UUID id,
        String handle,
        String nickname,
        String email,
        String profileImageUrl,
        UserRole role,
        UserStatus status,
        OffsetDateTime createdAt,
        long reportsReceivedCount, // 해당 유저가 받은 누적 신고 건수
        String activeSanction, // 현재 활성 제재 상태 ("경고", "7일 정지" 등 또는 "없음")
        UUID activePenaltyId // 현재 활성 제재가 있을 경우 제재 ID (해제용)
) {
    public static AdminUserResponse of(
            User user,
            long reportsReceivedCount,
            String activeSanction,
            UUID activePenaltyId) {
        String profileUrl = (user.getProfile() != null) ? user.getProfile().getProfileImageUrl() : null;

        return new AdminUserResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                user.getEmail(),
                profileUrl,
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                reportsReceivedCount,
                activeSanction != null ? activeSanction : "없음",
                activePenaltyId);
    }
}