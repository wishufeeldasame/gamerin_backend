package com.gamerin.backend.domain.mentoring.controller;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;

import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramUpdateRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramDetailResponse;
import com.gamerin.backend.domain.mentoring.dto.response.MentoringProgramResponse;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;


@Tag(name = "Mentoring", description = "멘토링 관련 API")
@RestController
@RequestMapping("/api/v1/mentoring")
@SecurityRequirement(name = "bearerAuth")
public class MentoringController {
    
    private final MentoringService mentoringService;

    public MentoringController(MentoringService mentoringService) {
        this.mentoringService = mentoringService;
    }

    @Operation(summary = "멘토 등록", description = "일반 유저를 멘토로 등록")
    @PostMapping("/mentors")
    public ApiResponse<MentorProfileResponse> registerMentor(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @Valid @RequestBody MentorRegistrationRequest request
    ) {
        return ApiResponse.ok(mentoringService.registerMentor(principal, request));
    }

    @Operation(summary = "멘토링 프로그램 등록", description = "멘토가 새로운 멘토링 상품을 등록")
    @PostMapping("/programs")
    public ApiResponse<MentoringProgramResponse> registerProgram(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @Valid @RequestBody MentoringProgramRequest request
    ) {
        return ApiResponse.ok(mentoringService.registerProgram(principal, request));
    }
    
    @Operation(summary = "멘토링 프로그램 목록 조회", description = "전체 또는 특정 게임의 멘토링 프로그램 목록을 조회(페이징 지원)")
    @GetMapping("/programs")
    public ApiResponse<Page<MentoringProgramResponse>> getPrograms(
        @RequestParam(required = false) String gameName,
        @ParameterObject @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(mentoringService.getPrograms(gameName, pageable));
    }

    @Operation(summary = "멘토링 프로그램 상세 조회", description = "특정 멘토링 프로그램의 상세 정보를 조회")
    @GetMapping("/programs/{id}")
    public ApiResponse<MentoringProgramDetailResponse> getProgramDetail(@PathVariable UUID id) {
        return ApiResponse.ok(mentoringService.getProgramDetail(id));
    }

    @Operation(summary = "멘토링 프로그램 수정", description = "자신이 등록한 멘토링 상품 정보를 수정")
    @PatchMapping("/programs/{id}")
    public ApiResponse<MentoringProgramResponse> updateProgram(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @PathVariable UUID id,
        @Valid @RequestBody MentoringProgramUpdateRequest request
    ) {
        return ApiResponse.ok(mentoringService.updateProgram(principal, id, request));
    }

    @Operation(summary = "멘토링 프로그램 삭제", description = "자신이 등록한 멘토링 상품을 삭제")
    @DeleteMapping("/programs/{id}")
    public ApiResponse<Void> deleteProgram(
        @AuthenticationPrincipal CustomUserPrincipal principal,
        @PathVariable UUID id
    ) {
        mentoringService.deleteProgram(principal, id);
        return ApiResponse.ok(null);
    }
    
}
