package com.gamerin.backend.domain.pubg.model;

public record RankedStats(
        Double kd,
        int roundsPlayed,
        Integer wins,
        String currentTier,
        String currentSubTier
) {
}
