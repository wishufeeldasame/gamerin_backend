package com.gamerin.backend.domain.pubg.controller;

import com.gamerin.backend.domain.pubg.dto.request.PubgConnectRequest;
import com.gamerin.backend.domain.pubg.dto.response.PubgConnectionResponse;
import com.gamerin.backend.domain.pubg.dto.response.PubgSummaryResponse;
import com.gamerin.backend.domain.pubg.service.PubgService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pubg")
public class PubgController {

    private final PubgService pubgService;

    public PubgController(PubgService pubgService) {
        this.pubgService = pubgService;
    }

    @PostMapping("/connect")
    public ApiResponse<PubgConnectionResponse> connect(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody PubgConnectRequest request
    ) {
        return ApiResponse.ok(pubgService.connect(principal, request));
    }

    @GetMapping("/me")
    public ApiResponse<PubgSummaryResponse> getMyPubgSummary(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(pubgService.getMySummary(principal));
    }

    @DeleteMapping("/disconnect")
    public ApiResponse<Void> disconnect(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        pubgService.disconnect(principal);
        return ApiResponse.ok(null);
    }
}
