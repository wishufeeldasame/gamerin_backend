package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.post.entity.PostMediaType;

public record ProfileMediaItemResponse(
        UUID mediaId,
        UUID postId,
        String authorHandle,
        PostMediaType mediaType,
        String mediaUrl,
        String thumbnailUrl,
        OffsetDateTime createdAt
) {
}
