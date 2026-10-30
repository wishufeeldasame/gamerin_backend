package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.admin.dto.response.AdminAuditLogResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminDashboardStatsResponse;
import com.gamerin.backend.domain.admin.service.AdminDashboardService;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 어드민 대시보드 통계 지표 및 감사 로그 조회 REST 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Dashboard & Audit", description = "어드민 전용 대시보드 통계 및 감사 로그 API")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping("/dashboard/stats")
    @Operation(
            summary = "어드민 대시보드 통계 요약 조회",
            description = "상태별 신고 건수(접수대기/검토중/완료/반려), 활성 제재 유저 수, 누적 숨김 콘텐츠 수를 조회합니다."
    )
    public ApiResponse<AdminDashboardStatsResponse> getDashboardStats() {
        return ApiResponse.ok(adminDashboardService.getDashboardStats());
    }

    @GetMapping("/audit-logs")
    @Operation(
            summary = "관리자 감사 로그(Audit Log) 목록 조회",
            description = "관리자가 수행한 액션(숨김, 복구, 제재, 환불, 정산)의 이력을 필터링 및 페이징 조회합니다."
    )
    public ApiResponse<Page<AdminAuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) UUID adminId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) ReportTargetType targetType,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(adminDashboardService.getAuditLogs(adminId, actionType, targetType, pageable));
    }
}