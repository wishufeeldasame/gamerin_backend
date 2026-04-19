package com.gamerin.backend.domain.pubg.dto.external;

import java.util.List;

public record PubgPlayerLookupResponse(
        List<PlayerData> data
) {
    public record PlayerData(
            String id,
            PlayerAttributes attributes
    ) {
    }

    public record PlayerAttributes(
            String name
    ) {
    }
}
