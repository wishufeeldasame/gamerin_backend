package com.gamerin.backend.domain.weeklyreport.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Membership", description = "멤버십 이용권 API")
@RestController
@RequestMapping("/api/v1/memberships")
@SecurityRequirement(name = "bearerAuth")
public class WeeklyReportController {

    private final WeeklyReportService weeklyReportService;

    public WeeklyReportController(WeeklyReportService weeklyReportService) {
        this.weeklyReportService = weeklyReportService;
    }

    @Operation(summary = "내 주간 전적 리포트 조회",
            description = "활성 멤버 본인의 직전 완료 주(KST 월~일) 발로란트 경쟁전 리포트를 조회합니다. 리포트가 없으면 games가 비어 있습니다.")
    @GetMapping("/weekly-report")
    public ApiResponse<WeeklyGameReportResponse> getMyWeeklyReport(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(weeklyReportService.getMyReport(principal.getUserId()));
    }
}
