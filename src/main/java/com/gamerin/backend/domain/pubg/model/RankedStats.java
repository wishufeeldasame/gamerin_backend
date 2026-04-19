package com.gamerin.backend.domain.pubg.model;

public record RankedStats(
        double kda,
        int roundsPlayed,
        int wins,
        String currentTier,
        String currentSubTier
) {
}
