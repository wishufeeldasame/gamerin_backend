package com.gamerin.backend.domain.riot.controller;
    
    import com.gamerin.backend.domain.riot.dto.request.RiotConnectRequest;
    import com.gamerin.backend.domain.riot.dto.response.RiotConnectionResponse;
    import com.gamerin.backend.domain.riot.dto.response.RiotSummaryResponse;
    import com.gamerin.backend.domain.riot.service.RiotService;
    import com.gamerin.backend.global.response.ApiResponse;
    import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
    import io.swagger.v3.oas.annotations.Operation;
    import io.swagger.v3.oas.annotations.security.SecurityRequirement;
    import io.swagger.v3.oas.annotations.tags.Tag;
    import jakarta.validation.Valid;
    import org.springframework.security.core.annotation.AuthenticationPrincipal;
    import org.springframework.web.bind.annotation.DeleteMapping;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PostMapping;
    import org.springframework.web.bind.annotation.RequestBody;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RestController;
    
    @RestController
    @RequestMapping("/api/v1/riot")
    @SecurityRequirement(name = "bearerAuth")
    @Tag(name = "Riot", description = "Riot Games API 연동 및 전적 조회 API") // 그룹 및 설명 설정
    public class RiotController {
    
        private final RiotService riotService;
    
        public RiotController(RiotService riotService) {
            this.riotService = riotService;
        }
    
        @PostMapping("/connect")
        @Operation(
                summary = "Riot 계정 연동",
                description = "사용자의 Riot ID(닉네임#태그)를 인증하여 계정 정보(PUUID)를 회원 프로필과 연동합니다."
        )
        public ApiResponse<RiotConnectionResponse> connect(
                @AuthenticationPrincipal CustomUserPrincipal principal,
                @Valid @RequestBody RiotConnectRequest request
        ) {
            return ApiResponse.ok(riotService.connect(principal, request));
        }

        @GetMapping("/lol/me")
        @Operation(
                summary = "League of Legends 요약 전적 조회",
                description = "연동된 Riot 계정의 솔로랭크/자유랭크 티어, 최근 5게임 평균 KDA, 총 게임 수 및 승률을 조회합니다."
        )
        public ApiResponse<RiotSummaryResponse> getMyLolSummary(
                @AuthenticationPrincipal CustomUserPrincipal principal
        ) {
            return ApiResponse.ok(riotService.getLolSummary(principal));
        }

        @DeleteMapping("/disconnect")
        @Operation(
                summary = "Riot 계정 연동 해제",
                description = "연동된 Riot 계정 데이터 및 저장된 LoL, Valorant 요약 정보를 전체 삭제합니다."
        )
        public ApiResponse<Void> disconnect(
                @AuthenticationPrincipal CustomUserPrincipal principal
        ) {
            riotService.disconnect(principal);
            return ApiResponse.ok(null);
        }
    }