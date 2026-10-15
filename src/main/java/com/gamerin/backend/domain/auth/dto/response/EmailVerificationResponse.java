package com.gamerin.backend.domain.auth.dto.response;

public record EmailVerificationResponse(
        long expiresInSeconds,
        long resendCooldownSeconds
) {
}
