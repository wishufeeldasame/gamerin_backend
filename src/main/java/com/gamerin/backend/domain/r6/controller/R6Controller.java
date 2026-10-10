package com.gamerin.backend.domain.r6.controller;

import com.gamerin.backend.domain.r6.dto.request.R6ConnectRequest;
import com.gamerin.backend.domain.r6.dto.response.R6ConnectionResponse;
import com.gamerin.backend.domain.r6.dto.response.R6SummaryResponse;
import com.gamerin.backend.domain.r6.service.R6Service;
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
@RequestMapping("/api/v1/r6")
@SecurityRequirement(name = "bearerAuth")
public class R6Controller {

    private final R6Service r6Service;

    public R6Controller(R6Service r6Service) {
        this.r6Service = r6Service;
    }

    @PostMapping("/connect")
    public ApiResponse<R6ConnectionResponse> connect(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody R6ConnectRequest request
    ) {
        return ApiResponse.ok(r6Service.connect(principal, request));
    }

    @GetMapping("/me")
    public ApiResponse<R6SummaryResponse> getMyR6Summary(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(r6Service.getMySummary(principal));
    }

    @PostMapping("/me/refresh")
    public ApiResponse<R6SummaryResponse> refreshMyR6Summary(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(r6Service.refreshMySummary(principal));
    }

    @DeleteMapping("/disconnect")
    public ApiResponse<Void> disconnect(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        r6Service.disconnect(principal);
        return ApiResponse.ok(null);
    }
}
