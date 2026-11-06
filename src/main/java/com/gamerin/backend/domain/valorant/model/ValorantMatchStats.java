package com.gamerin.backend.domain.valorant.model;

/** 경쟁전 한 경기에서 연동한 플레이어의 기록. rounds는 그 경기의 전체 라운드 수다. */
public record ValorantMatchStats(
        int kills,
        int deaths,
        int assists,
        int score,
        int rounds,
        int headshots,
        int bodyshots,
        int legshots,
        boolean won
) {
}
