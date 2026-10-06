// 어드민 전용 신고 목록 검색 조회 및 신고 상태 변경 API 컨트롤러
package com.gamerin.backend.domain.report.controller;

import com.gamerin.backend.domain.report.dto.request.ReportSearchCondition;
import com.gamerin.backend.domain.report.dto.request.ReportStatusUpdateRequest;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.service.ReportService;
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

@RestController
@RequestMapping("/api/v1/admin/reports")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Report", description = "어드민 전용 신고 관리 API")
public class AdminReportController {

    private final ReportService reportService;

    public AdminReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    @Operation(summary = "어드민 신고 목록 조회", description = "신고 상태, 대상 유형, 사유 코드, 검색 키워드 필터링 및 페이징 목록을 조회합니다.")
    public ApiResponse<Page<ReportResponse>> getAdminReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(required = false) ReportReasonCode reasonCode,
            @RequestParam(required = false) String keyword,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        ReportSearchCondition condition = new ReportSearchCondition(status, targetType, reasonCode, keyword);
        return ApiResponse.ok(reportService.getAdminReports(condition, pageable));
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "어드민 신고 상세 조회", description = "신고 건의 상세 정보(신고자, 피신고자, 스냅샷 내용, 처리 상태 등)를 단건 조회합니다.")
    public ApiResponse<ReportResponse> getAdminReportById(@PathVariable UUID reportId) {
        return ApiResponse.ok(reportService.getAdminReportById(reportId));
    }

    @PatchMapping("/{reportId}/status")
    @Operation(summary = "신고 상태 변경", description = "신고 건의 상태(RECEIVED, IN_REVIEW, RESOLVED, REJECTED)를 변경하고 담당 어드민을 할당합니다.")
    public ApiResponse<ReportResponse> updateReportStatus(
            @PathVariable UUID reportId,
            @Valid @RequestBody ReportStatusUpdateRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(reportService.updateReportStatus(reportId, request, principal));
    }

    @GetMapping("/{idOrCode}/detail")
    @Operation(summary = "어드민 신고 통합 상세 조회", description = "신고자 정보, 피신고자 정보, 콘텐츠 숨김 여부를 포함한 상세 데이터를 조회합니다.")
    public ApiResponse<com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse> getAdminReportDetail(
            @PathVariable String idOrCode) {
        return ApiResponse.ok(reportService.getAdminReportDetail(idOrCode));
    }

    @PostMapping("/{idOrCode}/start-review")
    @Operation(summary = "신고 검토 시작", description = "신고 상태를 IN_REVIEW(검토 중)로 변경하고 담당 관리자를 배정합니다.")
    public ApiResponse<com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse> startReportReview(
            @PathVariable String idOrCode,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(reportService.startReportReview(principal.getUserId(), idOrCode));
    }

    @PostMapping("/{idOrCode}/resolve")
    @Operation(summary = "신고 원클릭 통합 판정 처리", description = "상태 변경, 콘텐츠 숨김, 유저 제재를 동일 트랜잭션 내에서 일괄 처리합니다.")
    public ApiResponse<com.gamerin.backend.domain.report.dto.response.AdminReportDetailResponse> resolveReport(
            @PathVariable String idOrCode,
            @Valid @RequestBody com.gamerin.backend.domain.report.dto.request.AdminReportResolutionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(reportService.resolveReport(principal.getUserId(), idOrCode, request));
    }
}
