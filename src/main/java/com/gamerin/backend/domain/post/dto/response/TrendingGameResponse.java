package com.gamerin.backend.domain.post.dto.response;

public record TrendingGameResponse(
        String gameName,
        long postCount
) {
}
