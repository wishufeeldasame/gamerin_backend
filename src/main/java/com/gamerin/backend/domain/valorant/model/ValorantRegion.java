package com.gamerin.backend.domain.valorant.model;

import java.util.Locale;

/** 지원 리전(HenrikDev affinity). 플랫폼은 PC만 지원한다. */
public enum ValorantRegion {
    KR,
    AP;

    public String affinity() {
        return name().toLowerCase(Locale.ROOT);
    }
}
