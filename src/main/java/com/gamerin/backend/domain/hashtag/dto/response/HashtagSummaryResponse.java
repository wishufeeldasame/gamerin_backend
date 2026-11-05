package com.gamerin.backend.domain.hashtag.dto.response;

import java.util.UUID;

public record HashtagSummaryResponse(
        UUID hashtagId,
        String name,
        long postCount
) {
}
