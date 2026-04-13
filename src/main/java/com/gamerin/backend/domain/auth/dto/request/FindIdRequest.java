package com.gamerin.backend.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record FindIdRequest(
        @NotBlank
        @Email
        String email
) {
}
