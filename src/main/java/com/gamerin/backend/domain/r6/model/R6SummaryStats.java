package com.gamerin.backend.domain.r6.model;

import com.gamerin.backend.domain.game.model.GameStatsMode;

public record R6SummaryStats(
        String tierLabel,
        Double kd,
        Double winRate,
        Integer matches,
        GameStatsMode statsMode
) {
}
