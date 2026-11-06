package com.gamerin.backend.domain.valorant.dto.response;

import java.time.OffsetDateTime;

/** kd·acs·headshotRate·winRate·matches는 최근 경쟁전(최대 10경기) 기준이다. */
public record ValorantSummaryResponse(
        String game,
        boolean connected,
        String riotId,
        String region,
        String platform,
        String tierLabel,
        String peakTierLabel,
        Integer rr,
        Double kd,
        Integer acs,
        Integer headshotRate,
        Integer winRate,
        Integer matches,
        OffsetDateTime updatedAt
) {
}
