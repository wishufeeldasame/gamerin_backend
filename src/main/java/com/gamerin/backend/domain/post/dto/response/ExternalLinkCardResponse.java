package com.gamerin.backend.domain.post.dto.response;

public record ExternalLinkCardResponse(
        String url,
        String host,
        String title,
        String description,
        String thumbnailUrl
) {
}
