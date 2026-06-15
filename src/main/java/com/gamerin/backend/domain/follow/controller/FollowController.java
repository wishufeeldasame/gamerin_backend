package com.gamerin.backend.domain.follow.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.follow.dto.response.FollowUserResponse;
import com.gamerin.backend.domain.follow.service.FollowService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PostMapping("/{handle}/follow")
    public ApiResponse<Void> follow(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle
    ) {
        followService.follow(principal, handle);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{handle}/follow")
    public ApiResponse<Void> unfollow(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle
    ) {
        followService.unfollow(principal, handle);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{handle}/followers")
    public ApiResponse<CursorPageResponse<FollowUserResponse>> getFollowers(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(followService.getFollowers(principal, handle, cursor, size));
    }

    @GetMapping("/{handle}/following")
    public ApiResponse<CursorPageResponse<FollowUserResponse>> getFollowing(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String handle,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(followService.getFollowing(principal, handle, cursor, size));
    }
}
