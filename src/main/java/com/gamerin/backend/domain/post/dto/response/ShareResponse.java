package com.gamerin.backend.domain.post.dto.response;

import java.util.UUID;

public record ShareResponse(
        UUID postId,
        long shares
) {
}
