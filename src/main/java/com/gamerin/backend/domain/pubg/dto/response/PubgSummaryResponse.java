package com.gamerin.backend.domain.pubg.dto.response;

public record PubgSummaryResponse(
        String gameName,
        String tierLabel,
        double kda,
        int winRate,
        int games,
        boolean connected
) {
}
