package com.gamerin.backend.domain.bookmark.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RenameBookmarkCollectionRequest(
        @NotBlank(message = "Collection name is required.")
        String name
) {
}
