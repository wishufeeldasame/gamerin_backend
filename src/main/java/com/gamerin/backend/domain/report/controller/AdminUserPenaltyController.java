package com.gamerin.backend.domain.report.controller;

import com.gamerin.backend.domain.report.dto.request.UserPenaltyCreateRequest;
import com.gamerin.backend.domain.report.dto.response.UserPenaltyResponse;
import com.gamerin.backend.domain.report.service.UserPenaltyService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 어드민 전용 유저 제재(경고/정지) 부여, 수동 해제 및 제재 내역 조회 REST 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - User Penalty", description = "어드민 전용 유저 제재(경고/정지) 관리 API")
public class AdminUserPenaltyController {

    private final UserPenaltyService userPenaltyService;

    public AdminUserPenaltyController(UserPenaltyService userPenaltyService) {
        this.userPenaltyService = userPenaltyService;
    }

    @PostMapping("/users/{userId}/penalties")
    @Operation(summary = "유저 제재(경고/정지) 부여", description = "특정 유저에게 경고 또는 7일/30일/영구 정지 제재를 부여합니다.")
    public ApiResponse<UserPenaltyResponse> createPenalty(
            @PathVariable UUID userId,
            @Valid @RequestBody UserPenaltyCreateRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(userPenaltyService.createPenalty(principal.getUserId(), userId, request));
    }

    @DeleteMapping("/users/{userId}/penalties/{penaltyId}")
    @Operation(summary = "유저 제재 수동 해제", description = "부여된 활성 제재를 수동으로 조기 해제하고, 필요 시 계정을 정상(ACTIVE) 복구합니다.")
    public ApiResponse<UserPenaltyResponse> revokePenalty(
            @PathVariable UUID userId,
            @PathVariable UUID penaltyId,
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(userPenaltyService.revokePenalty(principal.getUserId(), userId, penaltyId));
    }

    @GetMapping("/users/{userId}/penalties")
    @Operation(summary = "특정 유저의 제재 내역 목록 조회", description = "특정 유저에게 부여되었던 제재 이력 전체를 최신순으로 페이징 조회합니다.")
    public ApiResponse<Page<UserPenaltyResponse>> getUserPenalties(
            @PathVariable UUID userId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(userPenaltyService.getUserPenalties(userId, pageable));
    }

    @GetMapping("/penalties/active")
    @Operation(summary = "현재 활성 제재 전체 목록 조회", description = "시스템 전체에서 현재 적용 중인 활성 제재 목록을 페이징 조회합니다.")
    public ApiResponse<Page<UserPenaltyResponse>> getActivePenalties(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(userPenaltyService.getActivePenalties(pageable));
    }
}