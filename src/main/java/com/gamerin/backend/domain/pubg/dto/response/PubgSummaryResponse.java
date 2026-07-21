package com.gamerin.backend.domain.pubg.dto.response;

import com.gamerin.backend.domain.game.model.GameStatsMode;

public record PubgSummaryResponse(
        String game,
        boolean connected,
        String playerName,
        String tierLabel,
        Double kd,
        Integer winRate,
        Integer matches,
        GameStatsMode statsMode
) {
}
