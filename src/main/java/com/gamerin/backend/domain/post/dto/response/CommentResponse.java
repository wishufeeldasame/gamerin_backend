package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommentResponse(
        UUID commentId,
        String author,
        String authorHandle,
        String authorProfileImageUrl,
        boolean authorVerifiedBadge,
        String content,
        OffsetDateTime createdAt
) {
}
