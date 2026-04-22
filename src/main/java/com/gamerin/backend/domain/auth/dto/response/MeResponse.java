package com.gamerin.backend.domain.auth.dto.response;

import java.util.UUID;

public record MeResponse(
        UUID userId,
        String handle,
        String nickname,
        String role,
        String status
) {
}
