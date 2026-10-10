package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.admin.dto.request.SystemConfigUpdateRequest;
import com.gamerin.backend.domain.admin.dto.response.SystemConfigResponse;
import com.gamerin.backend.domain.admin.service.AdminSettingsService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 어드민 전용 시스템 설정 조회 및 수정 API 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Settings", description = "어드민 전용 시스템 설정 관리 API")
public class AdminSettingsController {

    private final AdminSettingsService adminSettingsService;

    public AdminSettingsController(AdminSettingsService adminSettingsService) {
        this.adminSettingsService = adminSettingsService;
    }

    @GetMapping
    @Operation(summary = "시스템 설정 전체 목록 조회", description = "현재 등록된 모든 시스템 설정(자동 숨김 임계치, 기한 등) 목록을 조회합니다.")

    public ApiResponse<List<SystemConfigResponse>> getAllSettings() {
        return ApiResponse.ok(adminSettingsService.getAllConfigs());
    }

    @PatchMapping("/{configKey}")
    @Operation(summary = "시스템 설정 단건 수정", description = "특정 설정 키의 값을 수정하고 감사 로그를 남깁니다.")
    public ApiResponse<SystemConfigResponse> updateSetting(
            @PathVariable String configKey,
            @Valid @RequestBody SystemConfigUpdateRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse
                .ok(adminSettingsService.updateConfig(principal.getUserId(), configKey, request.configValue()));
    }

    @PutMapping
    @Operation(summary = "시스템 설정 다건 일괄 저장",description="프론트엔드 설정 화면에서 변경된 여러 설정값(키-값 쌍)을일괄 저장합니다.")

    public ApiResponse<List<SystemConfigResponse>> updateMultipleSettings(
            @RequestBody Map<String, String> configs,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(adminSettingsService.updateMultipleConfigs(principal.getUserId(), configs));
    }
}