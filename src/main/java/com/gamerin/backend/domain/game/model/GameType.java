package com.gamerin.backend.domain.game.model;

/** 서비스가 지원하는 게임 코드. 선언 순서가 /api/v1/games 응답 순서다. */
public enum GameType {
    PUBG("PUBG"),
    R6("Rainbow Six Siege"),
    LOL("League of Legends"),
    VALORANT("Valorant"),
    OVERWATCH("Overwatch"),
    CS2("CS2"),
    OTHER("기타");

    private final String displayName;

    GameType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
