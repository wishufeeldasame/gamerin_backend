package com.gamerin.backend.domain.valorant.dto.response;

import com.gamerin.backend.domain.valorant.model.ValorantRegion;

public record ValorantConnectionResponse(boolean connected, String riotId, ValorantRegion region, String platform) {
}
