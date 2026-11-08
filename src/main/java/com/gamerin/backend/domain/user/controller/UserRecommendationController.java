package com.gamerin.backend.domain.user.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.user.dto.response.UserRecommendationResponse;
import com.gamerin.backend.domain.user.service.UserRecommendationService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
public class UserRecommendationController {

    private final UserRecommendationService recommendationService;

    public UserRecommendationController(UserRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Operation(summary = "추천 친구 조회", description = "FOF 공통 연결 수 내림차순, 최근 로그인(없으면 가입) 시각과 ID 내림차순. "
            + "부족분은 최근 로그인/가입 순으로 보충하며 mutualCount는 0. 자기 자신·기존 팔로우·탈퇴·정지 계정 제외.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/api/v1/users/me/recommendations")
    public ApiResponse<List<UserRecommendationResponse>> getRecommendations(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Parameter(description = "조회 수. 범위 밖이면 400", schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "20", defaultValue = "5"))
            @RequestParam(defaultValue = "5") int limit
    ) {
        return ApiResponse.ok(recommendationService.recommend(principal.getUserId(), limit));
    }
}
