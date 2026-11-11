package com.gamerin.backend.domain.mentoring.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Mentoring", description = "멘토링 관련 API")
@RestController
@RequestMapping("/api/v1/mentoring")
public class MentoringProgramImageController {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;

    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final LightweightSecurityScanService lightweightSecurityScanService;
    private final MediaStorageService mediaStorageService;

    public MentoringProgramImageController(
            MediaUploadSecurityService mediaUploadSecurityService,
            LightweightSecurityScanService lightweightSecurityScanService,
            MediaStorageService mediaStorageService
    ) {
        this.mediaUploadSecurityService = mediaUploadSecurityService;
        this.lightweightSecurityScanService = lightweightSecurityScanService;
        this.mediaStorageService = mediaStorageService;
    }

    public record ProgramImageUploadResponse(String imageUrl) {
    }

    @Operation(summary = "멘토링 프로그램 설명 이미지 업로드", description = "프로그램 상세 설명에 넣을 이미지를 업로드하고 공개 URL을 반환")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(value = "/program-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProgramImageUploadResponse> uploadProgramImage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드할 이미지 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 10MB 이하로 업로드해주세요.");
        }
        lightweightSecurityScanService.assertFileClean(file);
        MediaStorageService.PreparedMediaFile prepared = mediaUploadSecurityService.prepareImage(file);
        try {
            MediaStorageService.StoredFile stored = mediaStorageService.storePostMedia(prepared);
            return ApiResponse.ok(new ProgramImageUploadResponse(stored.publicUrl()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "이미지를 저장하지 못했습니다.", ex);
        }
    }
}