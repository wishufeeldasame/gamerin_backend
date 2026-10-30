package com.gamerin.backend.domain.admin.controller;

import com.gamerin.backend.domain.admin.dto.request.AdminForceActionRequest;
import com.gamerin.backend.domain.admin.dto.request.AdminProgramStatusUpdateRequest;
import com.gamerin.backend.domain.admin.dto.response.AdminMentorResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringProgramResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminMentoringSummaryResponse;
import com.gamerin.backend.domain.admin.service.AdminMentoringService;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringApplicationResponse;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.mentoring.entity.ProgramStatus;
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
 * 어드민 전용 멘토링 운영 관리 (통계, 멘토 심사, 프로그램 관리, 에스크로 개입) REST 컨트롤러
 */
@RestController
@RequestMapping("/api/v1/admin/mentoring")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Mentoring Management", description = "어드민 전용 멘토링 운영 관리 및 에스크로 개입 API")
public class AdminMentoringController {

    private final AdminMentoringService adminMentoringService;

    public AdminMentoringController(AdminMentoringService adminMentoringService) {
        this.adminMentoringService = adminMentoringService;
    }

    // ================= [1. 멘토링 운영 요약 지표] =================

    @GetMapping("/summary")
    @Operation(summary = "멘토링 운영 요약 지표 조회", description = "승인 대기 신청 수, 운영 중 프로그램 수, 이번 달 세션 수, 에스크로 정산 예정액을 조회합니다.")
    public ApiResponse<AdminMentoringSummaryResponse> getSummary() {
        return ApiResponse.ok(adminMentoringService.getMentoringSummary());
    }

    // ================= [2. 멘토 심사 및 관리] =================

    @GetMapping("/mentors")
    @Operation(summary = "멘토 심사/관리 목록 조회"
    ,description="멘토 신청자 및 프로필 목록을 상태별로페이징 조회합니다.")
    public ApiResponse<Page<AdminMentorResponse>> getMentors(
            @RequestParam(required = false) MentorStatus status,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminMentoringService.getMentors(status, pageable));
    }

    @PostMapping("/mentors/{userId}/approve")
    @Operation(summary = "멘토 신청 승인"
    ,description="멘토 신청을 승인하여 프로그램을 개설할 수 있도록활성화합니다.")public ApiResponse<AdminMentorResponse> approveMentor(
            @PathVariable UUID userId,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(adminMentoringService.approveMentor(principal.getUserId(), userId));
    }

    @PostMapping("/mentors/{userId}/reject")
    @Operation(summary = "멘토 신청 반려/비활성화"
    ,description="멘토 신청을 반려하거나 계정을비활성화합니다.")public ApiResponse<AdminMentorResponse> rejectMentor(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(adminMentoringService.rejectMentor(principal.getUserId(), userId, request.reason()));
    }

    // ================= [3. 멘토링 프로그램 관리] =================

    @GetMapping("/programs")
    @Operation(summary = "멘토링 프로그램 관리 목록 조회"
    ,description="전체 프로그램 목록을 상태, 세션 수,신고 건수와 함께 페이징 조회합니다.")
    public ApiResponse<Page<AdminMentoringProgramResponse>> getPrograms(
            @RequestParam(required = false) ProgramStatus status,
            @RequestParam(required = false) String keyword,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminMentoringService.getPrograms(status, keyword, pageable));
    }

    @PatchMapping("/programs/{programId}/status")
    @Operation(summary = "프로그램 운영 상태 변경", description = "프로그램을 운영 중(ACTIVE) 또는 일시정지(CLOSED)로 변경합니다.")
    public ApiResponse<AdminMentoringProgramResponse> updateProgramStatus(
            @PathVariable UUID programId,
            @Valid @RequestBody AdminProgramStatusUpdateRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(adminMentoringService.updateProgramStatus(principal.getUserId(), programId,
                request.status()));
    }

    @PostMapping("/programs/{programId}/hide")
    @Operation(summary = "프로그램 관리자 강제 숨김" ,description="부적절하거나 신고된 프로그램을 관리자권한으로 강제 숨김(소프트 삭제) 처리합니다.")
    public ApiResponse<AdminMentoringProgramResponse> hideProgram(
            @PathVariable UUID programId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(adminMentoringService.hideProgram(principal.getUserId(), programId, request.reason()));
    }

    // ================= [4. 분쟁 에스크로 강제 개입 (기존)] =================

    @PostMapping("/applications/{applicationId}/force-refund")
    @Operation(summary = "멘토링 에스크로 강제 환불" ,description="분쟁 발생 시 관리자 권한으로 보관 중인마일리지를 멘티에게 전액 강제 환불합니다.")
    public ApiResponse<MentoringApplicationResponse> forceRefund(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse
                .ok(adminMentoringService.forceRefund(principal.getUserId(), applicationId, request.reason()));
    }

    @PostMapping("/applications/{applicationId}/force-settle")
    @Operation(summary = "멘토링 에스크로 강제 정산", description="분쟁 해결 시 관리자 권한으로 보관 중인마일리지를 멘토에게 강제 지급(정산)합니다.")
    public ApiResponse<MentoringApplicationResponse> forceSettle(
            @PathVariable UUID applicationId,
            @Valid @RequestBody AdminForceActionRequest request,
            @AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse
                .ok(adminMentoringService.forceSettle(principal.getUserId(), applicationId, request.reason()));
    }
}