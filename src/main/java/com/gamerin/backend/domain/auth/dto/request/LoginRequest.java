package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
<<<<<<< HEAD
        @NotBlank String handle,
        @NotBlank String password
=======
    @NotBlank String handle,
    @NotBlank String password
>>>>>>> main
) {
}
