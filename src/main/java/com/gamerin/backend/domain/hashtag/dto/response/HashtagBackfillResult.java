package com.gamerin.backend.domain.hashtag.dto.response;

public record HashtagBackfillResult(
        long processedPosts,
        int batches
) {
}
