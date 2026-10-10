package com.gamerin.backend.domain.bookmark.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record CreateBookmarkCollectionRequest(
        @NotBlank(message = "Collection name is required.")
        String name,
        UUID initialPostId
) {
}
