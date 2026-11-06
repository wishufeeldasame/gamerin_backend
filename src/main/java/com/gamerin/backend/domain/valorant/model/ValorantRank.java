package com.gamerin.backend.domain.valorant.model;

/** tierLabel은 HenrikDev tier.name 그대로다(예: Gold 2, Unrated). */
public record ValorantRank(String tierLabel, String peakTierLabel, Integer rr) {
}
