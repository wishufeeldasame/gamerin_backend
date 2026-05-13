package com.gamerin.backend.domain.mentoring.controller;

import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.response.MentorProfileResponse;
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
    
    
}
