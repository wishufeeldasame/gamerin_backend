package com.gamerin.backend.domain.valorant.controller;

import com.gamerin.backend.domain.valorant.dto.request.ValorantConnectRequest;
import com.gamerin.backend.domain.valorant.dto.response.ValorantConnectionResponse;
import com.gamerin.backend.domain.valorant.dto.response.ValorantSummaryResponse;
import com.gamerin.backend.domain.valorant.service.ValorantService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/valorant")
@SecurityRequirement(name = "bearerAuth")
public class ValorantController {

    private final ValorantService valorantService;

    public ValorantController(ValorantService valorantService) {
        this.valorantService = valorantService;
    }

    @PostMapping("/connect")
    public ApiResponse<ValorantConnectionResponse> connect(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody ValorantConnectRequest request
    ) {
        return ApiResponse.ok(valorantService.connect(principal, request));
    }

    @GetMapping("/me")
    public ApiResponse<ValorantSummaryResponse> getMyValorantSummary(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(valorantService.getMySummary(principal));
    }

    @PostMapping("/me/refresh")
    public ApiResponse<ValorantSummaryResponse> refreshMyValorantSummary(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(valorantService.refreshMySummary(principal));
    }

    @DeleteMapping("/disconnect")
    public ApiResponse<Void> disconnect(@AuthenticationPrincipal CustomUserPrincipal principal) {
        valorantService.disconnect(principal);
        return ApiResponse.ok(null);
    }
}
