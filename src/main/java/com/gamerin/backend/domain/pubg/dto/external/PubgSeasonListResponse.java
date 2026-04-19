package com.gamerin.backend.domain.pubg.dto.external;

import java.util.List;

public record PubgSeasonListResponse(
        List<SeasonData> data
) {
    public record SeasonData(
            String id,
            SeasonAttributes attributes
    ) {
    }

    public record SeasonAttributes(
            boolean isCurrentSeason,
            boolean isOffseason
    ) {
    }
}
