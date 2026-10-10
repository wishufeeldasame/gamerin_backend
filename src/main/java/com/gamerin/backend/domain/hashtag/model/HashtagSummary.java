package com.gamerin.backend.domain.hashtag.model;

import java.util.UUID;

public record HashtagSummary(
        UUID hashtagId,
        String displayName,
        long postCount
) {
}
