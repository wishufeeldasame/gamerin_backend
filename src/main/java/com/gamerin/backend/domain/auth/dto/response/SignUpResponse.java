package com.gamerin.backend.domain.auth.dto.response;

import java.util.UUID;

public record SignUpResponse(
    UUID userId,
    String handle,
    String nickname
) {
}
