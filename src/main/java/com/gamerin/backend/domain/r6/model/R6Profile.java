package com.gamerin.backend.domain.r6.model;

public record R6Profile(
        String playerName,
        String accountId,
        R6SummaryStats summary
) {
}
