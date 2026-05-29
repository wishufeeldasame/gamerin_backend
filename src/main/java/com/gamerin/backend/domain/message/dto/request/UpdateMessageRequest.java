package com.gamerin.backend.domain.message.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMessageRequest(
        @NotBlank
        @Size(max = 2000)
        String content
) {
}
