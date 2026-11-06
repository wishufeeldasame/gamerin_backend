package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record CommentResponse(
        UUID commentId,
        String author,
        String authorHandle,
        String authorProfileImageUrl,
        boolean authorVerifiedBadge,
        boolean authorMembershipBadge,
        RepresentativeTierResponse authorRepresentativeTier,
        String content,
        OffsetDateTime createdAt,
        boolean mine
) {
}
