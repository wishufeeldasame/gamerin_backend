package com.gamerin.backend.domain.r6.dto.response;

import java.time.OffsetDateTime;

public record R6SummaryResponse(
        String game,
        boolean connected,
        String playerName,
        String platform,
        String tierLabel,
        Double kd,
        Double winRate,
        Integer matches,
        OffsetDateTime updatedAt
) {
}
