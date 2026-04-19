package com.gamerin.backend.domain.pubg.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PubgConnectRequest(
        @NotBlank(message = "PUBG player name is required.")
        String playerName
) {
}
