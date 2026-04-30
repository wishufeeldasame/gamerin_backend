package com.gamerin.backend.domain.pubg.dto.external;

import java.util.Map;

public record PubgRankedStatsResponse(
        RankedData data
) {
    public record RankedData(
            RankedAttributes attributes
    ) {
    }

    public record RankedAttributes(
            Map<String, RankedGameModeStats> rankedGameModeStats
    ) {
    }
}
