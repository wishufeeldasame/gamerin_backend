package com.gamerin.backend.domain.r6.dto.response;

import java.time.OffsetDateTime;

import com.gamerin.backend.domain.game.model.GameStatsMode;

public record R6SummaryResponse(
        String game,
        boolean connected,
        String playerName,
        String tierLabel,
        Double kd,
        Integer winRate,
        Integer matches,
        GameStatsMode statsMode,
        String platform,
        OffsetDateTime updatedAt
) {
}
