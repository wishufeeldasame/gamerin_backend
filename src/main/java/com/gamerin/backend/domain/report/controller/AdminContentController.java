package com.gamerin.backend.domain.report.controller;

import java.util.UUID;

import com.gamerin.backend.domain.report.dto.response.HiddenContentResponse;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.service.ReportService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/contents")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Content", description = "어드민 전용 숨김 콘텐츠 관리 API")
public class AdminContentController {

    private final ReportService reportService;

    public AdminContentController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/hidden")
    @Operation(summary = "자동 숨김 처리된 콘텐츠 목록 조회", description = "신고 누적 임계값 초과로 자동 숨김(isHidden=true) 처리된 콘텐츠 목록을 조회합니다.")
    public ApiResponse<Page<HiddenContentResponse>> getHiddenContents(
            @PageableDefault(sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(reportService.getHiddenContents(pageable));
    }

    @PostMapping("/{targetType}/{targetId}/restore")
    @Operation(summary = "숨김 처리된 콘텐츠 복구", description = "자동 숨김 처리된 콘텐츠를 복구(isHidden=false)하고 관리자 감사 로그를 남깁니다.")
    public ApiResponse<HiddenContentResponse> restoreHiddenContent(
            @PathVariable ReportTargetType targetType,
            @PathVariable UUID targetId,
            @AuthenticationPrincipal CustomUserPrincipal principal // 관리자 인증 정보 주입
    ) {
        // 복구를 실행한 관리자의 ID를 서비스로 전달
        return ApiResponse.ok(reportService.restoreHiddenContent(targetType, targetId, principal.getUserId()));
    }
}