package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.valorant.dto.request.ValorantConnectRequest;
import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportService;
import com.gamerin.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 전용 주간 전적 리포트 수동 생성(시연용) API 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin/weekly-reports")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Weekly Reports", description = "어드민 전용 주간 전적 리포트 API")
public class AdminWeeklyReportController {

    private final WeeklyReportService weeklyReportService;

    public AdminWeeklyReportController(WeeklyReportService weeklyReportService) {
        this.weeklyReportService = weeklyReportService;
    }

    @PostMapping("/valorant")
    @Operation(summary = "발로란트 주간 리포트 수동 생성",
            description = "Riot ID(이름#태그)와 지역으로 직전 완료 주 리포트를 즉시 수집·집계·분석해 반환합니다. 리포트가 완성되면 해당 계정을 연동한 활성 멤버십 회원에게 완료 알림을 한 번 보냅니다. 경기 기록 없음·실패에는 알리지 않습니다. 외부 API 호출 한도에 따라 수 분 이상 걸릴 수 있으며, nginx의 이 경로 전용 대기 시간은 1시간입니다.")
    public ApiResponse<WeeklyGameReportResponse> generateValorantReport(
            @Valid @RequestBody ValorantConnectRequest request) {
        return ApiResponse.ok(weeklyReportService.generateForRiotId(request.riotId(), request.region()));
    }
}
