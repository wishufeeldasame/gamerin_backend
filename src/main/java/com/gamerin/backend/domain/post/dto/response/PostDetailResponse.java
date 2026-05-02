package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PostDetailResponse(
        UUID postId,
        String author,
        String authorHandle,
        String authorProfileImageUrl,
        boolean authorVerifiedBadge,
        String game,
        String content,
        List<PostMediaResponse> media,
        ExternalLinkCardResponse externalLink,
        long likes,
        long comments,
        long shares,
        boolean likedByMe,
        boolean mine,
        OffsetDateTime createdAt
) {
}
