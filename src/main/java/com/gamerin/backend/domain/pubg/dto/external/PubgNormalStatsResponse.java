package com.gamerin.backend.domain.pubg.dto.external;

import java.util.Map;

public record PubgNormalStatsResponse(
        NormalData data
) {
    public record NormalData(
            NormalAttributes attributes
    ) {
    }

    public record NormalAttributes(
            Map<String, NormalGameModeStats> gameModeStats
    ) {
    }
}
