package com.gamerin.backend.domain.user.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.ProfileMediaItemResponse;
import com.gamerin.backend.domain.post.service.FeedService;
import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;
import com.gamerin.backend.domain.user.dto.request.UpdateProfileRequest;
import com.gamerin.backend.domain.user.dto.response.DetailedUserProfileResponse;
import com.gamerin.backend.domain.user.dto.response.ProfileImageUploadResponse;
import com.gamerin.backend.domain.user.service.UserService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid; // 추가됨

@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;
    private final FeedService feedService;

    public UserController(UserService userService, FeedService feedService) {
        this.userService = userService;
        this.feedService = feedService;
    }

    @GetMapping("/me")
    public ApiResponse<DetailedUserProfileResponse> getMyProfile(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        DetailedUserProfileResponse response = userService.getMyProfile(principal.getUserId());
        return ApiResponse.ok(response);
    }

    @PatchMapping("/me")
    public ApiResponse<Void> updateProfile(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        userService.updateProfile(principal.getUserId(), request);
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/me/profile-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProfileImageUploadResponse> uploadProfileImage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam ProfileImageTarget target,
            @RequestParam MultipartFile file
    ) {
        return ApiResponse.ok(userService.uploadProfileImage(principal.getUserId(), target, file));
    }
    
    @GetMapping("/me/bookmarks")
    public ApiResponse<CursorPageResponse<PostCardResponse>> getMyBookmarks(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(feedService.getMyBookmarks(principal, cursor, size));
    }

    @GetMapping("/{handle}")
    public ApiResponse<DetailedUserProfileResponse> getProfile(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle
    ) {
        UUID viewerId = principal != null ? principal.getUserId() : null;
        return ApiResponse.ok(userService.getProfile(viewerId, handle));
    }

    @GetMapping("/{handle}/posts")
    public ApiResponse<CursorPageResponse<PostCardResponse>> getUserPosts(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(feedService.getUserPosts(principal, handle, cursor, size));
    }

    @GetMapping("/{handle}/media")
    public ApiResponse<CursorPageResponse<ProfileMediaItemResponse>> getUserMedia(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "24") int size
    ) {
        return ApiResponse.ok(feedService.getUserMedia(principal, handle, cursor, size));
    }

}
