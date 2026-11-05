package com.gamerin.backend.domain.pubg.dto.external;

import com.fasterxml.jackson.annotation.JsonAlias;

public record NormalGameModeStats(
        Integer roundsPlayed,
        Integer wins,
        @JsonAlias({"kda", "killDeathRatio"})
        Double kda,
        Integer kills,
        Integer deaths,
        Integer losses
) {
}
