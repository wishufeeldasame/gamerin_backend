package com.gamerin.backend.domain.auth.dto.response;


public record AuthTokenResponse(
        Long userId,
        String handle,
        String nickname,
        String accessToken,
        long accessTokenExpiresIn
) {
}
