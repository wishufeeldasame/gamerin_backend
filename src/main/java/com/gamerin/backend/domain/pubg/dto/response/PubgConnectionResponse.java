package com.gamerin.backend.domain.pubg.dto.response;

public record PubgConnectionResponse(
        boolean connected,
        String playerName
) {
}
