package com.gamerin.backend.domain.report.controller;

import com.gamerin.backend.domain.report.dto.request.ReportCreateRequest;
import com.gamerin.backend.domain.report.dto.response.ReportReasonResponse;
import com.gamerin.backend.domain.report.dto.response.ReportResponse;
import com.gamerin.backend.domain.report.service.ReportService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 
 * 일반 유저용 신고 접수 및 신고 사유 목록 조회 API 컨트롤러 
 */
@RestController
@RequestMapping("/api/v1/reports")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Report", description = "일반 유저 신고 관련 API")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/reasons")
    @Operation(summary = "신고 사유 목록 조회", description = "신고 팝업/모달에서 사용할 사유 코드 및 라벨 목록을 조회합니다.")
    public ApiResponse<List<ReportReasonResponse>> getReportReasons() {
        return ApiResponse.ok(reportService.getReportReasons());
    }

    @GetMapping("/me")
    @Operation(summary = "내 신고 내역 목록 조회", description = "로그인한 유저가 본인이 접수한 신고 목록을 최신순으로 페이징 조회합니다.")
    public ApiResponse<org.springframework.data.domain.Page<ReportResponse>> getMyReports(
                @AuthenticationPrincipal CustomUserPrincipal principal,
                @org.springframework.data.web.PageableDefault(sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) org.springframework.data.domain.Pageable pageable
        ) {
            return ApiResponse.ok(reportService.getMyReports(principal, pageable));
        }

    @PostMapping
    @Operation(summary = "통합 신고 접수", description = "게시글, 댓글, 유저, 멘토링, 메시지에 대한 신고를 접수합니다.")
    public ApiResponse<ReportResponse> createReport(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody ReportCreateRequest request
    ) {
        return ApiResponse.ok(reportService.createReport(principal, request));
    }
}