package com.gamerin.backend.domain.upload.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.upload.dto.response.UploadJobResponse;
import com.gamerin.backend.domain.upload.entity.UploadJob;
import com.gamerin.backend.domain.upload.service.UploadJobService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@RestController
@RequestMapping("/api/v1")
public class UploadJobController {
    private final UploadJobService service;
    public UploadJobController(UploadJobService service) { this.service = service; }

    @GetMapping("/posts/upload-jobs/{jobId}")
    public ApiResponse<UploadJobResponse> post(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable UUID jobId) {
        return ApiResponse.ok(service.get(principal, UploadJob.Kind.POST, jobId, false));
    }
    @GetMapping("/posts/upload-jobs/by-client-id/{key}")
    public ApiResponse<UploadJobResponse> postByKey(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable UUID key) {
        return ApiResponse.ok(service.get(principal, UploadJob.Kind.POST, key, true));
    }
    @GetMapping("/messages/upload-jobs/{jobId}")
    public ApiResponse<UploadJobResponse> message(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable UUID jobId) {
        return ApiResponse.ok(service.get(principal, UploadJob.Kind.MESSAGE, jobId, false));
    }
    @GetMapping("/messages/upload-jobs/by-client-id/{key}")
    public ApiResponse<UploadJobResponse> messageByKey(@AuthenticationPrincipal CustomUserPrincipal principal, @PathVariable UUID key) {
        return ApiResponse.ok(service.get(principal, UploadJob.Kind.MESSAGE, key, true));
    }
}
