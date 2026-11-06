package com.gamerin.backend.domain.user.dto.response;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.user.entity.UserProfile;

/** 닉네임 옆에 표시할 대표 게임 티어. tierLabel은 game_stats에 마지막으로 저장된 값을 그대로 쓴다. */
public record RepresentativeTierResponse(GameType game, String tierLabel) {

    /** 표시할 티어가 없으면 null을 반환한다. */
    public static RepresentativeTierResponse from(UserProfile profile) {
        String tierLabel = profile == null ? null : profile.getRepresentativeTierLabel();
        return tierLabel == null ? null : new RepresentativeTierResponse(profile.getRepresentativeGame(), tierLabel);
    }
}
