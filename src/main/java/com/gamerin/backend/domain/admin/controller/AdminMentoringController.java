package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.admin.dto.request.AdminForceActionRequest;
import com.gamerin.backend.domain.admin.service.AdminMentoringService;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 어드민 전용 멘토링 분쟁 에스크로 강제 개입(환불/정산) REST 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin/mentoring")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Mentoring Escrow", description = "어드민 전용 멘토링 에스크로 강제 개입 API")
public class AdminMentoringController {

    private final AdminMentoringService adminMentoringService;

    public AdminMentoringController(AdminMentoringService adminMentoringService) {
        this.adminMentoringService = adminMentoringService;
    }

    @PostMapping("/applications/{applicationId}/force-refund")
    @Operation(summary = "멘토링 에스크로 강제 환불", description = "분쟁 발생 시 관리자 권한으로 보관 중인 마일리지를 멘티에게 전액 강제 환불합니다.")
    public ApiResponse<MentoringApplicationResponse> forceRefund(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(adminMentoringService.forceRefund(principal.getUserId(), applicationId, request.reason()));
    }

    @PostMapping("/applications/{applicationId}/force-settle")
    @Operation(summary = "멘토링 에스크로 강제 정산", description = "분쟁 해결 시 관리자 권한으로 보관 중인 마일리지를 멘토에게 강제 지급(정산)합니다.")
    public ApiResponse<MentoringApplicationResponse> forceSettle(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(adminMentoringService.forceSettle(principal.getUserId(), applicationId, request.reason()));
    }
}