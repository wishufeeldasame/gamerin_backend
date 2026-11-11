package com.gamerin.backend.domain.valorant.model;

import java.time.Instant;

/** 경기 식별 정보를 붙인 경쟁전 기록. tierLabel은 그 경기 시점의 HenrikDev tier.name이다(없으면 null). insight는 계산 실패 시 null. */
public record ValorantMatchDetail(
        String matchId,
        Instant startedAt,
        String map,
        String agent,
        Integer tierId,
        String tierLabel,
        ValorantMatchStats stats,
        MatchInsight insight
) {
}
