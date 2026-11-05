package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.admin.dto.response.AdminUserResponse;
import com.gamerin.backend.domain.admin.service.AdminUserService;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 어드민 전용 사용자 목록 검색, 상세 조회 API 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Users", description = "어드민 전용 사용자 관리 API")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @Operation(summary = "어드민 사용자 목록 검색 및 페이징 조회", description = "핸들/닉네임 키워드 검색, 계정 상태, 활성 제재 여부 필터링 목록을 반환합니다.")

    public ApiResponse<Page<AdminUserResponse>> getAdminUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean hasSanction,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminUserService.getAdminUsers(query, status, hasSanction, pageable));
    }

    @GetMapping("/by-handle/{handle}")
    @Operation(summary = "사용자 핸들(handle)로 상세 조회", description="프론트엔드 /admin/users/[handle] 화면용 단건 상세조회 API입니다.")

    public ApiResponse<AdminUserResponse> getAdminUserByHandle(@PathVariable String handle) {
        return ApiResponse.ok(adminUserService.getAdminUserByHandle(handle));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "사용자 ID(UUID)로 상세 조회", description = "유저 ID 기준 단건 상세 조회 API입니다.")
    public ApiResponse<AdminUserResponse> getAdminUserById(@PathVariable UUID userId) {
        return ApiResponse.ok(adminUserService.getAdminUserById(userId));
    }
}