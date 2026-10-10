package com.gamerin.backend.domain.game.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.game.dto.GameResponse;
import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Game", description = "게임 코드 API")
@RestController
@RequestMapping("/api/v1/games")
@SecurityRequirement(name = "bearerAuth")
public class GameController {

    @Operation(summary = "게임 목록 조회", description = "지원하는 게임 코드와 표시명을 고정 순서로 반환 (로그인 필요)")
    @GetMapping
    public ApiResponse<List<GameResponse>> getGames() {
        return ApiResponse.ok(Arrays.stream(GameType.values()).map(GameResponse::from).toList());
    }
}
