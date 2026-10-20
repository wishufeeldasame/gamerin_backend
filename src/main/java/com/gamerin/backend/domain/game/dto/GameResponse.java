package com.gamerin.backend.domain.game.dto;

import com.gamerin.backend.domain.game.model.GameType;

public record GameResponse(String code, String name) {

    public static GameResponse from(GameType game) {
        return new GameResponse(game.name(), game.displayName());
    }
}
