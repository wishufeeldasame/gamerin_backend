package com.gamerin.backend.domain.post.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.repost.dto.response.ReposterInfoResponse;

public record PostDetailResponse(
        UUID postId,
        String author,
        String authorHandle,
        String authorProfileImageUrl,
        boolean authorVerifiedBadge,
        String content,
        List<PostMediaResponse> media,
        long likes,
        long comments,
        long shares,
        boolean isReposted,
        long repostCount,
        ReposterInfoResponse reposterInfo,
        boolean likedByMe,
        boolean bookmarkedByMe,
        boolean mine,
        OffsetDateTime createdAt
) {
}
