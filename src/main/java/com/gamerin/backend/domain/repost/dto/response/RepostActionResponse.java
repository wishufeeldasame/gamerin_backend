package com.gamerin.backend.domain.repost.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RepostActionResponse(
        UUID postId,
        boolean isReposted,
        long repostCount,
        OffsetDateTime repostedAt
) {
}
