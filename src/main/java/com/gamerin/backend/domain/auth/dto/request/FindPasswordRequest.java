package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FindPasswordRequest(
        @NotBlank
        String handle
) {
}
