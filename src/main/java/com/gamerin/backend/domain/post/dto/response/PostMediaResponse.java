package com.gamerin.backend.domain.post.dto.response;

import java.util.UUID;

import com.gamerin.backend.domain.post.entity.PostMediaType;

public record PostMediaResponse(
        UUID mediaId,
        PostMediaType mediaType,
        String mediaUrl,
        String thumbnailUrl,
        int sortOrder,
        Integer durationSeconds
) {
}
