package com.gamerin.backend.domain.bookmark.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.bookmark.dto.request.CreateBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.request.RenameBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkCollectionResponse;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkMembershipResponse;
import com.gamerin.backend.domain.bookmark.service.BookmarkCollectionService;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/bookmark-collections")
@SecurityRequirement(name = "bearerAuth")
public class BookmarkCollectionController {

    private final BookmarkCollectionService bookmarkCollectionService;

    public BookmarkCollectionController(BookmarkCollectionService bookmarkCollectionService) {
        this.bookmarkCollectionService = bookmarkCollectionService;
    }

    @PostMapping
    public ApiResponse<BookmarkCollectionResponse> create(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreateBookmarkCollectionRequest request
    ) {
        return ApiResponse.ok(bookmarkCollectionService.create(principal, request));
    }

    @GetMapping
    public ApiResponse<List<BookmarkCollectionResponse>> getCollections(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(required = false) UUID postId
    ) {
        return ApiResponse.ok(bookmarkCollectionService.getCollections(principal, postId));
    }

    @GetMapping("/{collectionId}")
    public ApiResponse<BookmarkCollectionResponse> getCollection(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId
    ) {
        return ApiResponse.ok(bookmarkCollectionService.getCollection(principal, collectionId));
    }

    @PatchMapping("/{collectionId}")
    public ApiResponse<BookmarkCollectionResponse> rename(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId,
            @Valid @RequestBody RenameBookmarkCollectionRequest request
    ) {
        return ApiResponse.ok(bookmarkCollectionService.rename(principal, collectionId, request));
    }

    @DeleteMapping("/{collectionId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId
    ) {
        bookmarkCollectionService.delete(principal, collectionId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{collectionId}/bookmarks")
    public ApiResponse<CursorPageResponse<PostCardResponse>> getCollectionBookmarks(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mediaOnly
    ) {
        return ApiResponse.ok(bookmarkCollectionService.getCollectionBookmarks(
                principal,
                collectionId,
                cursor,
                size,
                q,
                mediaOnly
        ));
    }

    @PutMapping("/{collectionId}/bookmarks/{postId}")
    public ApiResponse<BookmarkMembershipResponse> addPost(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId,
            @PathVariable UUID postId
    ) {
        return ApiResponse.ok(bookmarkCollectionService.addPost(principal, collectionId, postId));
    }

    @DeleteMapping("/{collectionId}/bookmarks/{postId}")
    public ApiResponse<BookmarkMembershipResponse> removePost(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID collectionId,
            @PathVariable UUID postId
    ) {
        return ApiResponse.ok(bookmarkCollectionService.removePost(principal, collectionId, postId));
    }
}
