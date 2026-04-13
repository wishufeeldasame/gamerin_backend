package com.gamerin.backend.domain.auth.dto.response;

import java.util.UUID;

public record AuthTokenResponse(
        UUID userId,
        String handle,
        String nickname,
        String accessToken,
        long accessTokenExpiresIn
) {
}
