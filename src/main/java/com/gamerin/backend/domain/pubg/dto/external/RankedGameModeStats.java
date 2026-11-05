package com.gamerin.backend.domain.pubg.dto.external;

public record RankedGameModeStats(
        Integer roundsPlayed,
        Integer wins,
        Double kda,
        Double kdr,
        TierInfo currentTier,
        Integer kills,
        Integer assists,
        Integer deaths
) {
}
