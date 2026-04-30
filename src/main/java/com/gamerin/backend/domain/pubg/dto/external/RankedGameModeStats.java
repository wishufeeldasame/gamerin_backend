package com.gamerin.backend.domain.pubg.dto.external;

public record RankedGameModeStats(
        int roundsPlayed,
        int wins,
        double kda,
        TierInfo currentTier
) {
}
