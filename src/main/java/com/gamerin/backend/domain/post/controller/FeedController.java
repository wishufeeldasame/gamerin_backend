package com.gamerin.backend.domain.post.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.dto.response.TrendingGameResponse;
import com.gamerin.backend.domain.post.service.FeedService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/feed")
@SecurityRequirement(name = "bearerAuth")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public ApiResponse<CursorPageResponse<PostCardResponse>> getFeed(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(feedService.getFeed(principal, tab, cursor, size));
    }

    @GetMapping("/trending/games")
    public ApiResponse<List<TrendingGameResponse>> getTrendingGames(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(feedService.getTrendingGames(principal));
    }
}
