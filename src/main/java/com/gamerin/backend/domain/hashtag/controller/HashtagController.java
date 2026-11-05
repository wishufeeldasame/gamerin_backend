package com.gamerin.backend.domain.hashtag.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagSummaryResponse;
import com.gamerin.backend.domain.hashtag.service.HashtagQueryService;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/hashtags")
@SecurityRequirement(name = "bearerAuth")
public class HashtagController {

    private final HashtagQueryService hashtagQueryService;

    public HashtagController(HashtagQueryService hashtagQueryService) {
        this.hashtagQueryService = hashtagQueryService;
    }

    @GetMapping
    public ApiResponse<List<HashtagSummaryResponse>> autocomplete(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(hashtagQueryService.autocomplete(principal, query, size));
    }

    @GetMapping("/{name}/posts")
    public ApiResponse<CursorPageResponse<PostCardResponse>> getPosts(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable String name,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(hashtagQueryService.getPosts(principal, name, cursor, size));
    }
}
