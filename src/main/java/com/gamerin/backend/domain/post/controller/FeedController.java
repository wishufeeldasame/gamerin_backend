package com.gamerin.backend.domain.post.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.service.FeedService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @Operation(description = "전체 피드는 비로그인 조회 가능. following 탭은 인증 필수이며 익명 요청은 401.")
    @GetMapping
    public ApiResponse<CursorPageResponse<PostCardResponse>> getFeed(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(feedService.getFeed(principal, tab, cursor, size));
    }
}
