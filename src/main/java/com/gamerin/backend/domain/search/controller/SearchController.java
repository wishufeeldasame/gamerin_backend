package com.gamerin.backend.domain.search.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagSummaryResponse;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.search.dto.response.SearchOverviewResponse;
import com.gamerin.backend.domain.search.service.SearchService;
import com.gamerin.backend.domain.user.dto.response.SimpleUserProfileResponse;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/search")
@SecurityRequirement(name = "bearerAuth")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ApiResponse<SearchOverviewResponse> overview(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(name = "q") String query,
            @RequestParam(defaultValue = "5") int size
    ) {
        return ApiResponse.ok(searchService.overview(principal, query, size));
    }

    @GetMapping("/accounts")
    public ApiResponse<CursorPageResponse<SimpleUserProfileResponse>> searchAccounts(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(name = "q") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(searchService.searchAccounts(principal, query, cursor, size));
    }

    @GetMapping("/posts")
    public ApiResponse<CursorPageResponse<PostCardResponse>> searchPosts(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(name = "q") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(searchService.searchPosts(principal, query, cursor, size));
    }

    @GetMapping("/hashtags")
    public ApiResponse<List<HashtagSummaryResponse>> searchHashtags(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(name = "q") String query,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(searchService.searchHashtags(principal, query, size));
    }
}
