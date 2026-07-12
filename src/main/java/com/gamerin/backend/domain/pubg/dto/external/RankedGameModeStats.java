package com.gamerin.backend.domain.pubg.dto.external;

import com.fasterxml.jackson.annotation.JsonAlias;

public record RankedGameModeStats(
        Integer roundsPlayed,
        Integer wins,
        @JsonAlias({"kda", "killDeathRatio"})
        Double kda,
        Double kdr,
        TierInfo currentTier,
        Integer kills,
        Integer assists,
        Integer deaths
) {
}
