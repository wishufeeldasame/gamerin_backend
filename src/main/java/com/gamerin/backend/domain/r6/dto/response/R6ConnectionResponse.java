package com.gamerin.backend.domain.r6.dto.response;

public record R6ConnectionResponse(
        boolean connected,
        String playerName,
        String platform
) {
}
