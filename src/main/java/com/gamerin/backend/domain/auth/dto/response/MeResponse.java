package com.gamerin.backend.domain.auth.dto.response;


public record MeResponse(
        Long userId,
        String handle,
        String nickname,
        String role,
        String status
) {
}
