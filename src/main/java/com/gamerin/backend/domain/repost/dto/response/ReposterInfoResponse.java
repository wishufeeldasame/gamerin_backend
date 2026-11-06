package com.gamerin.backend.domain.repost.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record ReposterInfoResponse(
        UUID userId,
        String nickname,
        OffsetDateTime repostedAt,
        boolean membershipBadge,
        RepresentativeTierResponse representativeTier
) {
}
