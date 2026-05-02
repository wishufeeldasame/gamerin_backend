package com.gamerin.backend.domain.post.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostMediaRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional
public class PostService {

    private static final int MAX_IMAGE_COUNT = 4;
    private static final int MAX_VIDEO_COUNT = 1;

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostResponseAssembler postResponseAssembler;

    public PostService(
            UserRepository userRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            PostLikeRepository postLikeRepository,
            PostCommentRepository postCommentRepository,
            PostResponseAssembler postResponseAssembler
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.postLikeRepository = postLikeRepository;
        this.postCommentRepository = postCommentRepository;
        this.postResponseAssembler = postResponseAssembler;
    }

    public PostDetailResponse create(CustomUserPrincipal principal, CreatePostRequest request) {
        User user = getCurrentUser(principal);
        String content = normalizeContent(request.content());
        List<CreatePostMediaRequest> mediaRequests = normalizeMediaRequests(request.media());

        validateCreateRequest(content, mediaRequests);

        Post post = Post.create(user, normalizeGameName(request.gameName()), content);
        Post savedPost = postRepository.save(post);

        if (!mediaRequests.isEmpty()) {
            List<PostMedia> mediaToSave = new ArrayList<>();
            for (int index = 0; index < mediaRequests.size(); index++) {
                CreatePostMediaRequest mediaRequest = mediaRequests.get(index);
                mediaToSave.add(PostMedia.create(
                        savedPost,
                        mediaRequest.mediaType(),
                        mediaRequest.mediaUrl().trim(),
                        normalizeOptionalText(mediaRequest.thumbnailUrl()),
                        mediaRequest.sortOrder() != null ? mediaRequest.sortOrder() : index,
                        mediaRequest.durationSeconds()
                ));
            }
            postMediaRepository.saveAll(mediaToSave);
        }

        return postResponseAssembler.toPostDetail(savedPost, user.getId());
    }

    @Transactional(readOnly = true)
    public PostDetailResponse getDetail(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);
        return postResponseAssembler.toPostDetail(post, user.getId());
    }

    public void like(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);

        if (postLikeRepository.existsByPostIdAndUserId(postId, user.getId())) {
            return;
        }

        postLikeRepository.save(PostLike.create(post, user));
        post.increaseLikeCount();
    }

    public void unlike(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);

        postLikeRepository.findByPostIdAndUserId(postId, user.getId())
                .ifPresent(like -> {
                    postLikeRepository.delete(like);
                    post.decreaseLikeCount();
                });
    }

    public CommentResponse createComment(CustomUserPrincipal principal, UUID postId, CreateCommentRequest request) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);
        String content = normalizeContent(request.content());

        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "댓글 내용을 입력해 주세요.");
        }

        PostComment savedComment = postCommentRepository.save(PostComment.create(post, user, content));
        post.increaseCommentCount();
        return postResponseAssembler.toCommentResponse(savedComment);
    }

    private void validateCreateRequest(String content, List<CreatePostMediaRequest> mediaRequests) {
        if (content == null && mediaRequests.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용 또는 미디어 중 하나는 반드시 입력해야 합니다.");
        }

        long imageCount = mediaRequests.stream()
                .filter(media -> media.mediaType() == PostMediaType.IMAGE)
                .count();
        long videoCount = mediaRequests.stream()
                .filter(media -> media.mediaType() == PostMediaType.VIDEO)
                .count();

        if (imageCount > 0 && videoCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지와 동영상은 동시에 업로드할 수 없습니다.");
        }
        if (imageCount > MAX_IMAGE_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 최대 4장까지 업로드할 수 있습니다.");
        }
        if (videoCount > MAX_VIDEO_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동영상은 1개만 업로드할 수 있습니다.");
        }

        for (CreatePostMediaRequest mediaRequest : mediaRequests) {
            if (mediaRequest.mediaType() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "미디어 타입을 지정해 주세요.");
            }
            if (mediaRequest.mediaType() == PostMediaType.VIDEO && isBlank(mediaRequest.thumbnailUrl())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동영상 썸네일 URL은 필수입니다.");
            }
            if (mediaRequest.durationSeconds() != null && mediaRequest.durationSeconds() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동영상 길이는 0보다 작을 수 없습니다.");
            }
        }
    }

    private List<CreatePostMediaRequest> normalizeMediaRequests(List<CreatePostMediaRequest> mediaRequests) {
        if (mediaRequests == null || mediaRequests.isEmpty()) {
            return List.of();
        }

        return mediaRequests.stream()
                .filter(media -> media != null && !isBlank(media.mediaUrl()))
                .sorted(Comparator.comparing(request -> request.sortOrder() != null ? request.sortOrder() : Integer.MAX_VALUE))
                .toList();
    }

    private Post getActivePost(UUID postId) {
        return postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."));
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private String normalizeGameName(String gameName) {
        String normalized = normalizeOptionalText(gameName);
        return normalized == null ? "GENERAL" : normalized;
    }

    private String normalizeContent(String content) {
        String normalized = normalizeOptionalText(content);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
