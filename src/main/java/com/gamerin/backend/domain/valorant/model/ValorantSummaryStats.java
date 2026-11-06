package com.gamerin.backend.domain.valorant.model;

/** game_stats.VALORANT에 저장하는 요약. kd·acs·headshotRate·winRate·matches는 최근 경쟁전 기준이다. */
public record ValorantSummaryStats(
        String tierLabel,
        String peakTierLabel,
        Integer rr,
        Double kd,
        Integer acs,
        Integer headshotRate,
        Integer winRate,
        Integer matches
) {
}
