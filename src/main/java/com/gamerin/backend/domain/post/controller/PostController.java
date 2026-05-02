package com.gamerin.backend.domain.post.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/posts")
@SecurityRequirement(name = "bearerAuth")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    public ApiResponse<PostDetailResponse> create(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreatePostRequest request
    ) {
        return ApiResponse.ok(postService.create(principal, request));
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostDetailResponse> getDetail(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId
    ) {
        return ApiResponse.ok(postService.getDetail(principal, postId));
    }

    @PostMapping("/{postId}/likes")
    public ApiResponse<Void> like(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId
    ) {
        postService.like(principal, postId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{postId}/likes")
    public ApiResponse<Void> unlike(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId
    ) {
        postService.unlike(principal, postId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{postId}/comments")
    public ApiResponse<CommentResponse> createComment(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return ApiResponse.ok(postService.createComment(principal, postId, request));
    }
}
