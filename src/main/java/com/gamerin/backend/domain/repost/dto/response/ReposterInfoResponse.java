package com.gamerin.backend.domain.repost.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReposterInfoResponse(
        UUID userId,
        String nickname,
        OffsetDateTime repostedAt
) {
}
