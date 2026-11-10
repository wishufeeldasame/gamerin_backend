package com.gamerin.backend.domain.valorant.model;

import java.time.Instant;

/** 최근 랭크 변동 이력의 경기 식별자. 상세 조회에서 빠진 경기를 대조하는 용도다. */
public record ValorantMatchReference(String matchId, Instant startedAt, String map) {
}
