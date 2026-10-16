package com.gamerin.backend.domain.auth.dto.response;

public record EmailVerificationConfirmResponse(
        String verificationToken,
        long expiresInSeconds
) {
}
