package com.gamerin.backend.domain.r6.model;

public record R6SummaryStats(
        String tierLabel,
        Double kd,
        Double winRate,
        Integer matches
) {
}
