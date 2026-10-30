package com.gamerin.backend.domain.bookmark.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookmarkCollectionResponse(
        UUID collectionId,
        String name,
        String coverImageUrl,
        long bookmarkCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean containsPost
) {
}
