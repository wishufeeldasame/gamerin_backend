package com.gamerin.backend.domain.auth.controller;

import com.gamerin.backend.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.gamerin.backend.domain.auth.dto.request.EmailVerificationRequest;
import com.gamerin.backend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.gamerin.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.gamerin.backend.domain.auth.service.EmailVerificationService;
import com.gamerin.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/email-verifications")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping
    public ApiResponse<EmailVerificationResponse> send(@Valid @RequestBody EmailVerificationRequest request) {
        return ApiResponse.ok(emailVerificationService.sendSignupCode(request.email()));
    }

    @PostMapping("/confirm")
    public ApiResponse<EmailVerificationConfirmResponse> confirm(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        return ApiResponse.ok(emailVerificationService.confirmSignupCode(request.email(), request.code()));
    }
}
