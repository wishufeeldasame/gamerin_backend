package com.gamerin.backend.domain.post.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateExternalLinkRequest(
        @NotBlank(message = "External link URL is required.")
        @Size(max = 2048, message = "External link URL must be 2048 characters or fewer.")
        String url
) {
}
