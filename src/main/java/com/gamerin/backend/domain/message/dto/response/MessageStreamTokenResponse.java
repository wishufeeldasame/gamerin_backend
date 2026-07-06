package com.gamerin.backend.domain.message.dto.response;

import java.time.OffsetDateTime;

public record MessageStreamTokenResponse(
        OffsetDateTime expiresAt
) {
}
