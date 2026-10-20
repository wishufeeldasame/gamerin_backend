package com.gamerin.backend.domain.auth.dto.response;

import java.util.UUID;

public record MeResponse(
        UUID userId,
        String handle,
        String nickname,
        String role,
        String status,
        // 소셜 전용 가입자는 비밀번호가 없어 비밀번호 변경을 제공하지 않는다
        boolean hasPassword,
        boolean membershipBadge
) {
}
