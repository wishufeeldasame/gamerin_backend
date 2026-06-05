package com.gamerin.backend.domain.message.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SharedPostPreviewResponse(
        UUID postId,
        String author,
        String authorHandle,
        String content,
        OffsetDateTime createdAt
) {
}
