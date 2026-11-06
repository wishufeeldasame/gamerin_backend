package com.gamerin.backend.domain.valorant.model;

/**
 * tierLabel은 HenrikDev tier.name 그대로다(예: Gold 2, Unrated).
 * riotId는 mmr 응답의 현재 name#tag다. 요청한 puuid와 다르거나 비었으면 null이다.
 */
public record ValorantRank(String tierLabel, String peakTierLabel, Integer rr, String riotId) {
}
