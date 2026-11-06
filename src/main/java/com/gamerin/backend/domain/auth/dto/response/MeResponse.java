package com.gamerin.backend.domain.auth.dto.response;

import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

public record MeResponse(
        UUID userId,
        String handle,
        String nickname,
        String role,
        String status,
        // 소셜 전용 가입자는 비밀번호가 없어 비밀번호 변경을 제공하지 않는다
        boolean hasPassword,
        boolean membershipBadge,
        // 설정 화면의 선택값. 티어가 없어도 고른 게임은 그대로 내려준다
        GameType representativeGame,
        RepresentativeTierResponse representativeTier
) {
}
