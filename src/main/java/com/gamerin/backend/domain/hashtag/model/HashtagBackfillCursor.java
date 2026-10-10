package com.gamerin.backend.domain.hashtag.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HashtagBackfillCursor(
        OffsetDateTime createdAt,
        UUID postId
) {
}
