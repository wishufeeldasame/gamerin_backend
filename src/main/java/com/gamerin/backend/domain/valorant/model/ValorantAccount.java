package com.gamerin.backend.domain.valorant.model;

public record ValorantAccount(String puuid, String name, String tag) {

    public String riotId() {
        return name + "#" + tag;
    }
}
