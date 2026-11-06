package com.gamerin.backend.domain.user.dto.request;

import com.gamerin.backend.domain.game.model.GameType;

/** game이 null이면 대표 게임 표시를 끈다. */
public record UpdateRepresentativeGameRequest(GameType game) {
}
