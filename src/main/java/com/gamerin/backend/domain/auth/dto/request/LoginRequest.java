package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String handle,
        @NotBlank String password
) {
}
