package com.gamerin.backend.domain.post.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateExternalLinkRequest;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostMediaRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostExternalLink;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostExternalLinkRepository;
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
    private final PostExternalLinkRepository postExternalLinkRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostResponseAssembler postResponseAssembler;
    private final MediaStorageService mediaStorageService;
    private final ExternalLinkMetadataService externalLinkMetadataService;

    public PostService(
            UserRepository userRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            PostExternalLinkRepository postExternalLinkRepository,
            PostLikeRepository postLikeRepository,
            PostCommentRepository postCommentRepository,
            PostResponseAssembler postResponseAssembler,
            MediaStorageService mediaStorageService,
            ExternalLinkMetadataService externalLinkMetadataService
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.postExternalLinkRepository = postExternalLinkRepository;
        this.postLikeRepository = postLikeRepository;
        this.postCommentRepository = postCommentRepository;
        this.postResponseAssembler = postResponseAssembler;
        this.mediaStorageService = mediaStorageService;
        this.externalLinkMetadataService = externalLinkMetadataService;
    }

    public PostDetailResponse create(CustomUserPrincipal principal, CreatePostRequest request) {
        User user = getCurrentUser(principal);
        String content = normalizeContent(request.content());
        List<CreatePostMediaRequest> mediaRequests = normalizeMediaRequests(request.media());
        CreateExternalLinkRequest externalLink = normalizeExternalLink(request.externalLink());

        validateCreateRequest(content, mediaRequests, externalLink);

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

        if (externalLink != null) {
            saveExternalLink(savedPost, externalLink.url());
        }

        return postResponseAssembler.toPostDetail(savedPost, user.getId());
    }

    public PostDetailResponse create(CustomUserPrincipal principal, CreateMultipartPostRequest request) {
        User user = getCurrentUser(principal);
        String content = normalizeContent(request.getContent());
        List<MultipartFile> mediaFiles = normalizeMultipartFiles(request.getMediaFiles());
        MultipartFile thumbnailFile = normalizeMultipartFile(request.getThumbnailFile());
        String externalLinkUrl = normalizeOptionalText(request.getExternalLinkUrl());

        validateMultipartCreateRequest(content, mediaFiles, thumbnailFile, request.getDurationSeconds(), externalLinkUrl);

        Post post = Post.create(user, normalizeGameName(request.getGameName()), content);
        Post savedPost = postRepository.save(post);

        if (!mediaFiles.isEmpty()) {
            saveUploadedMedia(savedPost, mediaFiles, thumbnailFile, request.getDurationSeconds());
        }

        if (externalLinkUrl != null) {
            saveExternalLink(savedPost, externalLinkUrl);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment content is required.");
        }

        PostComment savedComment = postCommentRepository.save(PostComment.create(post, user, content));
        post.increaseCommentCount();
        return postResponseAssembler.toCommentResponse(savedComment);
    }

    private void saveUploadedMedia(
            Post post,
            List<MultipartFile> mediaFiles,
            MultipartFile thumbnailFile,
            Integer durationSeconds
    ) {
        List<MediaStorageService.StoredFile> storedFiles = new ArrayList<>();

        try {
            PostMediaType mediaType = resolveMediaType(mediaFiles.getFirst());
            MediaStorageService.StoredFile storedThumbnail = null;
            if (mediaType == PostMediaType.VIDEO && thumbnailFile != null) {
                storedThumbnail = mediaStorageService.storePostMedia(thumbnailFile);
                storedFiles.add(storedThumbnail);
            }

            List<PostMedia> mediaToSave = new ArrayList<>();
            for (int index = 0; index < mediaFiles.size(); index++) {
                MediaStorageService.StoredFile storedMedia = mediaStorageService.storePostMedia(mediaFiles.get(index));
                storedFiles.add(storedMedia);
                mediaToSave.add(PostMedia.create(
                        post,
                        mediaType,
                        storedMedia.publicUrl(),
                        storedThumbnail != null ? storedThumbnail.publicUrl() : null,
                        index,
                        mediaType == PostMediaType.VIDEO ? durationSeconds : null
                ));
            }

            postMediaRepository.saveAll(mediaToSave);
        } catch (Exception ex) {
            storedFiles.forEach(mediaStorageService::deleteQuietly);
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload media files.", ex);
        }
    }

    private void saveExternalLink(Post post, String url) {
        ExternalLinkMetadataService.ExternalLinkMetadata metadata = externalLinkMetadataService.fetch(url);
        postExternalLinkRepository.save(PostExternalLink.create(
                post,
                metadata.url(),
                metadata.host(),
                metadata.title(),
                metadata.description(),
                metadata.thumbnailUrl()
        ));
    }

    private void validateCreateRequest(
            String content,
            List<CreatePostMediaRequest> mediaRequests,
            CreateExternalLinkRequest externalLink
    ) {
        if (content == null && mediaRequests.isEmpty() && externalLink == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content, media, or external link is required.");
        }

        if (!mediaRequests.isEmpty() && externalLink != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded media and external link cannot be used together.");
        }

        long imageCount = mediaRequests.stream()
                .filter(media -> media.mediaType() == PostMediaType.IMAGE)
                .count();
        long videoCount = mediaRequests.stream()
                .filter(media -> media.mediaType() == PostMediaType.VIDEO)
                .count();

        if (imageCount > 0 && videoCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Images and videos cannot be uploaded together.");
        }
        if (imageCount > MAX_IMAGE_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can upload up to 4 images.");
        }
        if (videoCount > MAX_VIDEO_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can upload only one video.");
        }

        for (CreatePostMediaRequest mediaRequest : mediaRequests) {
            if (mediaRequest.mediaType() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Media type is required.");
            }
            if (mediaRequest.mediaType() == PostMediaType.VIDEO && isBlank(mediaRequest.thumbnailUrl())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video uploads require a thumbnail URL.");
            }
            if (mediaRequest.durationSeconds() != null && mediaRequest.durationSeconds() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration seconds cannot be negative.");
            }
        }
    }

    private void validateMultipartCreateRequest(
            String content,
            List<MultipartFile> mediaFiles,
            MultipartFile thumbnailFile,
            Integer durationSeconds,
            String externalLinkUrl
    ) {
        if (content == null && mediaFiles.isEmpty() && externalLinkUrl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content, media, or external link is required.");
        }

        if (!mediaFiles.isEmpty() && externalLinkUrl != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded media and external link cannot be used together.");
        }

        if (externalLinkUrl != null) {
            if (thumbnailFile != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thumbnail file is only allowed for video uploads.");
            }
            if (durationSeconds != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration seconds is only allowed for video uploads.");
            }
        }

        if (mediaFiles.isEmpty()) {
            if (thumbnailFile != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thumbnail file requires a video upload.");
            }
            if (durationSeconds != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration seconds requires a video upload.");
            }
            return;
        }

        long imageCount = 0;
        long videoCount = 0;
        for (MultipartFile mediaFile : mediaFiles) {
            PostMediaType mediaType = resolveMediaType(mediaFile);
            if (mediaType == PostMediaType.IMAGE) {
                imageCount++;
            } else if (mediaType == PostMediaType.VIDEO) {
                videoCount++;
            }
        }

        if (imageCount > 0 && videoCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Images and videos cannot be uploaded together.");
        }
        if (imageCount > MAX_IMAGE_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can upload up to 4 images.");
        }
        if (videoCount > MAX_VIDEO_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can upload only one video.");
        }

        if (videoCount == 1) {
            if (thumbnailFile == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video uploads require a thumbnail file.");
            }
            if (resolveMediaType(thumbnailFile) != PostMediaType.IMAGE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video thumbnail must be an image file.");
            }
            if (durationSeconds == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video uploads require duration seconds.");
            }
        } else {
            if (thumbnailFile != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thumbnail file is only allowed for video uploads.");
            }
            if (durationSeconds != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration seconds is only allowed for video uploads.");
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

    private List<MultipartFile> normalizeMultipartFiles(List<MultipartFile> mediaFiles) {
        if (mediaFiles == null || mediaFiles.isEmpty()) {
            return List.of();
        }

        return mediaFiles.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
    }

    private MultipartFile normalizeMultipartFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return file;
    }

    private CreateExternalLinkRequest normalizeExternalLink(CreateExternalLinkRequest externalLink) {
        if (externalLink == null || isBlank(externalLink.url())) {
            return null;
        }
        return new CreateExternalLinkRequest(externalLink.url().trim());
    }

    private PostMediaType resolveMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("image/")) {
                return PostMediaType.IMAGE;
            }
            if (normalized.startsWith("video/")) {
                return PostMediaType.VIDEO;
            }
        }

        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String normalized = fileName.toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") || normalized.endsWith(".png")
                    || normalized.endsWith(".gif") || normalized.endsWith(".webp")) {
                return PostMediaType.IMAGE;
            }
            if (normalized.endsWith(".mp4") || normalized.endsWith(".mov") || normalized.endsWith(".webm")
                    || normalized.endsWith(".m4v")) {
                return PostMediaType.VIDEO;
            }
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media file type.");
    }

    private Post getActivePost(UUID postId) {
        return postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found."));
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
