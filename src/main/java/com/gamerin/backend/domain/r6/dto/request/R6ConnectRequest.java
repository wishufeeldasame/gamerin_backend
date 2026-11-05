package com.gamerin.backend.domain.r6.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record R6ConnectRequest(
        @NotBlank(message = "R6 playerName is required.")
        @Size(max = 100, message = "R6 playerName must be 100 characters or fewer.")
        String playerName
) {
}
