package com.gamerin.backend.domain.post.controller;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.upload.service.UploadJobService;
import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.dto.request.RecordPostViewsRequest;
import com.gamerin.backend.domain.post.dto.request.UpdatePostRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.dto.response.PostVideoJobResponse;
import com.gamerin.backend.domain.post.dto.response.RecordPostViewsResponse;
import com.gamerin.backend.domain.post.dto.response.ShareResponse;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.post.service.PostViewService;
import com.gamerin.backend.domain.repost.dto.response.RepostActionResponse;
import com.gamerin.backend.domain.repost.service.PostRepostService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    private static final Logger log = LoggerFactory.getLogger(PostController.class);

    private final PostService postService;
    private final UploadJobService uploadJobService;
    private final PostRepostService postRepostService;
    private final PostViewService postViewService;

    public PostController(PostService postService, PostRepostService postRepostService,
            PostViewService postViewService, UploadJobService uploadJobService) {
        this.postService = postService;
        this.uploadJobService = uploadJobService;
        this.postRepostService = postRepostService;
        this.postViewService = postViewService;
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PostDetailResponse> create(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreatePostRequest request) {
        return ApiResponse.ok(postService.create(principal, request));
    }

    /**
     * 기본 요청은 사진 200·영상 202이며 영상은 {@code GET /video-jobs/{jobId}}로 확인한다.
     * {@code asyncUpload=true}는 사진·영상 모두 검열 전 202로 접수하며 {@code GET /upload-jobs/{jobId}}로 확인한다.
     */
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<?>> createWithFiles(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @ModelAttribute CreateMultipartPostRequest request) {
        if (request.isAsyncUpload()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(uploadJobService.enqueuePost(principal, request)));
        }
        PostService.MultipartCreateResult result = postService.create(principal, request);
        if (result.videoJob() != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result.videoJob()));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.post()));
    }

    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/video-jobs/{jobId}")
    public ApiResponse<PostVideoJobResponse> getVideoJob(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID jobId) {
        return ApiResponse.ok(postService.getVideoJob(principal, jobId));
    }

    /**
     * KP-C3 (#92): 멤버십 게시물 본문 수정 API (E1).
     */
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping(value = "/{postId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PostDetailResponse> update(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId,
            @Valid @RequestBody UpdatePostRequest request) {
        return ApiResponse.ok(postService.update(principal, postId, request));
    }

    @GetMapping("/{postId}")
    public ApiResponse<PostDetailResponse> getDetail(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        PostDetailResponse detail = postService.getDetail(principal, postId);
        recordDetailView(principal, detail);
        return ApiResponse.ok(detail);
    }

    @GetMapping("/by-number/{postNumber}")
    public ApiResponse<PostDetailResponse> getDetailByNumber(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable long postNumber) {
        PostDetailResponse detail = postService.getDetailByNumber(principal, postNumber);
        recordDetailView(principal, detail);
        return ApiResponse.ok(detail);
    }

    /** 피드 카드 노출 보고. 24시간 중복·삭제·숨김 글은 세지 않고 조용히 건너뛴다. */
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/views")
    public ApiResponse<RecordPostViewsResponse> recordViews(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody RecordPostViewsRequest request) {
        return ApiResponse.ok(new RecordPostViewsResponse(
                postViewService.record(principal.getUserId(), request.postIds())));
    }

    // 읽기 응답은 이미 만들었으므로(이번 조회는 다음 응답부터 반영) 집계 실패가 상세 조회를 막지 않게 한다.
    private void recordDetailView(CustomUserPrincipal principal, PostDetailResponse detail) {
        if (principal == null) {
            return;
        }
        try {
            postViewService.record(principal.getUserId(), List.of(detail.postId()));
        } catch (RuntimeException ex) {
            log.warn("Failed to record post view postId={}", detail.postId(), ex);
        }
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{postId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        postService.delete(principal, postId);
        return ApiResponse.ok(null);
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{postId}/likes")
    public ApiResponse<Void> like(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        postService.like(principal, postId);
        return ApiResponse.ok(null);
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{postId}/likes")
    public ApiResponse<Void> unlike(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        postService.unlike(principal, postId);
        return ApiResponse.ok(null);
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{postId}/reposts")
    public ApiResponse<RepostActionResponse> repost(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        return ApiResponse.ok(postRepostService.repost(principal, postId));
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{postId}/reposts")
    public ApiResponse<RepostActionResponse> unrepost(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        return ApiResponse.ok(postRepostService.unrepost(principal, postId));
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{postId}/bookmarks")
    public ApiResponse<Void> bookmark(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        postService.bookmark(principal, postId);
        return ApiResponse.ok(null);
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{postId}/bookmarks")
    public ApiResponse<Void> unbookmark(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        postService.unbookmark(principal, postId);
        return ApiResponse.ok(null);
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{postId}/shares")
    public ApiResponse<ShareResponse> share(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId,
            @RequestBody(required = false) CreateShareRequest request) {
        return ApiResponse.ok(postService.share(principal, postId, request));
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{postId}/comments")
    public ApiResponse<CommentResponse> createComment(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        return ApiResponse.ok(postService.createComment(principal, postId, request));
    }

    @GetMapping("/{postId}/comments")
    public ApiResponse<List<CommentResponse>> getComments(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId) {
        return ApiResponse.ok(postService.getComments(principal, postId));
    }

    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{postId}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID postId,
            @PathVariable UUID commentId) {
        postService.deleteComment(principal, postId, commentId);
        return ApiResponse.ok(null);
    }
}
