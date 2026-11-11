package com.gamerin.backend.domain.mentoring.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
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

    private final MentorProfileRepository mentorProfileRepository;
    private final ContentModerationService contentModerationService;
    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final LightweightSecurityScanService lightweightSecurityScanService;
    private final MediaStorageService mediaStorageService;

    public MentoringProgramImageController(
            MentorProfileRepository mentorProfileRepository,
            ContentModerationService contentModerationService,
            MediaUploadSecurityService mediaUploadSecurityService,
            LightweightSecurityScanService lightweightSecurityScanService,
            MediaStorageService mediaStorageService
    ) {
        this.mentorProfileRepository = mentorProfileRepository;
        this.contentModerationService = contentModerationService;
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
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        MentorProfile mentor = mentorProfileRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "멘토로 등록되지 않은 사용자입니다."));
        if (mentor.getStatus() != MentorStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 승인이 완료된 멘토만 이미지를 업로드할 수 있습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드할 이미지 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 10MB 이하로 업로드해주세요.");
        }
        mediaUploadSecurityService.assertImageFileSafe(file);
        lightweightSecurityScanService.assertFileClean(file);
        contentModerationService.assertPostAllowed(null, List.of(file));
        MediaStorageService.PreparedMediaFile prepared = mediaUploadSecurityService.prepareImage(file);
        try {
            // TODO: 멘토링 프로그램 설명 이미지는 본문 마크다운 URL로만 참조되며 별도 미디어 테이블이 없다.
            // 작성 취소로 미사용된 고아 파일이나 삭제된 프로그램의 이미지 파일을 정리하는 배치/참조 추적은 추후 이슈로 분리한다.
            MediaStorageService.StoredFile stored = mediaStorageService.storePostMedia(prepared);
            return ApiResponse.ok(new ProgramImageUploadResponse(stored.publicUrl()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "이미지를 저장하지 못했습니다.", ex);
        }
    }
}