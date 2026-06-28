package com.gamerin.backend.domain.post.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.dto.response.ShareResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostShare;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.entity.ShareTarget;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.repository.PostShareRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional
public class PostService {

    private static final int MAX_IMAGE_COUNT = 4;
    private static final int MAX_VIDEO_COUNT = 1;
    private static final double MAX_VIDEO_DURATION_SECONDS = 120.0;

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final PostLikeRepository postLikeRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostShareRepository postShareRepository;
    private final PostResponseAssembler postResponseAssembler;
    private final MediaStorageService mediaStorageService;
    private final VideoMetadataService videoMetadataService;
    private final ContentModerationService contentModerationService;
    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final LightweightSecurityScanService lightweightSecurityScanService;
    private final TextSecurityService textSecurityService;
    private final VideoOptimizationService videoOptimizationService;
    private final long maxVideoFileSizeBytes;

    public PostService(
            UserRepository userRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            PostLikeRepository postLikeRepository,
            PostBookmarkRepository postBookmarkRepository,
            PostCommentRepository postCommentRepository,
            PostShareRepository postShareRepository,
            PostResponseAssembler postResponseAssembler,
            MediaStorageService mediaStorageService,
            VideoMetadataService videoMetadataService,
            ContentModerationService contentModerationService,
            MediaUploadSecurityService mediaUploadSecurityService,
            LightweightSecurityScanService lightweightSecurityScanService,
            TextSecurityService textSecurityService,
            VideoOptimizationService videoOptimizationService,
            @Value("${app.media.video.max-file-size-bytes:104857600}") long maxVideoFileSizeBytes
    ) {
        if (maxVideoFileSizeBytes < 1) {
            throw new IllegalArgumentException("Video max file size must be at least 1 byte.");
        }

        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.postLikeRepository = postLikeRepository;
        this.postBookmarkRepository = postBookmarkRepository;
        this.postCommentRepository = postCommentRepository;
        this.postShareRepository = postShareRepository;
        this.postResponseAssembler = postResponseAssembler;
        this.mediaStorageService = mediaStorageService;
        this.videoMetadataService = videoMetadataService;
        this.contentModerationService = contentModerationService;
        this.mediaUploadSecurityService = mediaUploadSecurityService;
        this.lightweightSecurityScanService = lightweightSecurityScanService;
        this.textSecurityService = textSecurityService;
        this.videoOptimizationService = videoOptimizationService;
        this.maxVideoFileSizeBytes = maxVideoFileSizeBytes;
    }

    public PostDetailResponse create(CustomUserPrincipal principal, CreatePostRequest request) {
        User user = getCurrentUser(principal);
        String content = normalizeContent(request.content());

        validateCreateRequest(content);
        textSecurityService.assertTextSafe(content);
        contentModerationService.assertTextAllowed(content);

        Post post = Post.create(user, content);
        Post savedPost = postRepository.save(post);

        return postResponseAssembler.toPostDetail(savedPost, user.getId());
    }

    public PostDetailResponse create(CustomUserPrincipal principal, CreateMultipartPostRequest request) {
        User user = getCurrentUser(principal);
        String content = normalizeContent(request.getContent());
        List<MultipartFile> mediaFiles = normalizeMultipartFiles(request.getMediaFiles());
        MultipartFile thumbnailFile = normalizeMultipartFile(request.getThumbnailFile());

        validateMultipartCreateRequest(content, mediaFiles, thumbnailFile);
        textSecurityService.assertTextSafe(content);
        contentModerationService.assertPostAllowed(content, mediaFiles);
        PreparedMediaUpload preparedMediaUpload = prepareMediaUpload(mediaFiles, thumbnailFile);

        try {
            Post post = Post.create(user, content);
            Post savedPost = postRepository.save(post);

            if (!preparedMediaUpload.isEmpty()) {
                saveUploadedMedia(savedPost, preparedMediaUpload);
            }

            return postResponseAssembler.toPostDetail(savedPost, user.getId());
        } catch (RuntimeException ex) {
            preparedMediaUpload.deleteTemporaryFiles(mediaStorageService);
            throw ex;
        }
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

    public void delete(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);

        if (!post.getAuthor().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can delete this post.");
        }

        post.softDelete();
    }

    public void bookmark(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);

        if (postBookmarkRepository.existsByPostIdAndUserId(postId, user.getId())) {
            return;
        }

        postBookmarkRepository.save(PostBookmark.create(post, user));
    }

    public void unbookmark(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        getActivePost(postId);

        postBookmarkRepository.findByPostIdAndUserId(postId, user.getId())
                .ifPresent(postBookmarkRepository::delete);
    }

    public ShareResponse share(CustomUserPrincipal principal, UUID postId, CreateShareRequest request) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);
        ShareTarget target = request != null ? request.normalizedTarget() : ShareTarget.COPY_LINK;

        postShareRepository.save(PostShare.create(post, user, target));
        post.increaseShareCount();

        return new ShareResponse(post.getId(), post.getShareCount());
    }

    public CommentResponse createComment(CustomUserPrincipal principal, UUID postId, CreateCommentRequest request) {
        User user = getCurrentUser(principal);
        Post post = getActivePost(postId);
        String content = normalizeContent(request.content());

        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment content is required.");
        }
        textSecurityService.assertTextSafe(content);
        contentModerationService.assertTextAllowed(content);

        PostComment savedComment = postCommentRepository.save(PostComment.create(post, user, content));
        post.increaseCommentCount();
        return postResponseAssembler.toCommentResponse(savedComment, user.getId());
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(CustomUserPrincipal principal, UUID postId) {
        User user = getCurrentUser(principal);
        getActivePost(postId);

        return postCommentRepository.findActiveByPostId(postId)
                .stream()
                .map(comment -> postResponseAssembler.toCommentResponse(comment, user.getId()))
                .toList();
    }

    public void deleteComment(CustomUserPrincipal principal, UUID postId, UUID commentId) {
        User user = getCurrentUser(principal);
        PostComment comment = postCommentRepository.findActiveByPostIdAndId(postId, commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found."));

        if (!comment.getAuthor().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the author can delete this comment.");
        }

        postCommentRepository.delete(comment);
        comment.getPost().decreaseCommentCount();
    }

    private void saveUploadedMedia(
            Post post,
            PreparedMediaUpload preparedMediaUpload
    ) {
        List<MediaStorageService.StoredFile> storedFiles = new ArrayList<>();

        try {
            MediaStorageService.StoredFile storedThumbnail = null;
            if (preparedMediaUpload.preparedThumbnailFile() != null) {
                storedThumbnail = mediaStorageService.storePostMedia(preparedMediaUpload.preparedThumbnailFile());
                storedFiles.add(storedThumbnail);
            }

            List<PostMedia> mediaToSave = new ArrayList<>();
            for (int index = 0; index < preparedMediaUpload.mediaCount(); index++) {
                MediaStorageService.StoredFile storedMedia;
                if (preparedMediaUpload.mediaType() == PostMediaType.IMAGE) {
                    storedMedia = mediaStorageService.storePostMedia(preparedMediaUpload.preparedImageFiles().get(index));
                } else {
                    storedMedia = mediaStorageService.storePostMedia(preparedMediaUpload.preparedVideoFiles().get(index));
                }
                storedFiles.add(storedMedia);
                mediaToSave.add(PostMedia.create(
                        post,
                        preparedMediaUpload.mediaType(),
                        storedMedia.publicUrl(),
                        storedThumbnail != null ? storedThumbnail.publicUrl() : null,
                        index
                ));
            }

            postMediaRepository.saveAll(mediaToSave);
        } catch (Exception ex) {
            storedFiles.forEach(mediaStorageService::deleteQuietly);
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload media files.", ex);
        } finally {
            preparedMediaUpload.deleteTemporaryFiles(mediaStorageService);
        }
    }

    private PreparedMediaUpload prepareMediaUpload(List<MultipartFile> mediaFiles, MultipartFile thumbnailFile) {
        if (mediaFiles.isEmpty()) {
            return PreparedMediaUpload.empty();
        }

        PostMediaType mediaType = resolveMediaType(mediaFiles.getFirst());
        if (mediaType == PostMediaType.IMAGE) {
            List<MediaStorageService.PreparedMediaFile> preparedImageFiles = mediaFiles.stream()
                    .map(mediaUploadSecurityService::prepareImage)
                    .toList();
            return new PreparedMediaUpload(mediaType, preparedImageFiles, List.of(), null);
        }

        List<MediaStorageService.PreparedMediaPath> preparedVideoFiles = mediaFiles.stream()
                .map(videoOptimizationService::prepareVideo)
                .toList();
        MediaStorageService.PreparedMediaFile preparedThumbnailFile = thumbnailFile == null
                ? null
                : mediaUploadSecurityService.prepareImage(thumbnailFile);
        return new PreparedMediaUpload(mediaType, List.of(), preparedVideoFiles, preparedThumbnailFile);
    }

    private void validateCreateRequest(String content) {
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content is required.");
        }
    }

    private void validateMultipartCreateRequest(
            String content,
            List<MultipartFile> mediaFiles,
            MultipartFile thumbnailFile
    ) {
        if (content == null && mediaFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content or media is required.");
        }

        if (mediaFiles.isEmpty()) {
            if (thumbnailFile != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thumbnail file requires a video upload.");
            }
            return;
        }

        long imageCount = 0;
        long videoCount = 0;
        for (MultipartFile mediaFile : mediaFiles) {
            PostMediaType mediaType = resolveMediaType(mediaFile);
            if (mediaType == PostMediaType.IMAGE) {
                imageCount++;
                mediaUploadSecurityService.assertImageFileSafe(mediaFile);
                lightweightSecurityScanService.assertFileClean(mediaFile);
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
            if (thumbnailFile != null && resolveMediaType(thumbnailFile) != PostMediaType.IMAGE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video thumbnail must be an image file.");
            }
            if (thumbnailFile != null) {
                mediaUploadSecurityService.assertImageFileSafe(thumbnailFile);
                lightweightSecurityScanService.assertFileClean(thumbnailFile);
            }
            validateVideoConstraints(mediaFiles.getFirst());
        } else {
            if (thumbnailFile != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thumbnail file is only allowed for video uploads.");
            }
        }
    }

    private void validateVideoConstraints(MultipartFile videoFile) {
        if (videoFile.getSize() > maxVideoFileSizeBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video file exceeds the configured size limit.");
        }

        mediaUploadSecurityService.assertVideoFileSafe(videoFile);
        lightweightSecurityScanService.assertFileClean(videoFile);

        double videoLengthSeconds = videoMetadataService.readDurationSeconds(videoFile);
        if (videoLengthSeconds > MAX_VIDEO_DURATION_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video duration must be 2 minutes or shorter.");
        }
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
            if (normalized.endsWith(".mp4") || normalized.endsWith(".mov")
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

    private record PreparedMediaUpload(
            PostMediaType mediaType,
            List<MediaStorageService.PreparedMediaFile> preparedImageFiles,
            List<MediaStorageService.PreparedMediaPath> preparedVideoFiles,
            MediaStorageService.PreparedMediaFile preparedThumbnailFile
    ) {

        private static PreparedMediaUpload empty() {
            return new PreparedMediaUpload(null, List.of(), List.of(), null);
        }

        private boolean isEmpty() {
            return preparedImageFiles.isEmpty() && preparedVideoFiles.isEmpty();
        }

        private int mediaCount() {
            return mediaType == PostMediaType.IMAGE ? preparedImageFiles.size() : preparedVideoFiles.size();
        }

        private void deleteTemporaryFiles(MediaStorageService mediaStorageService) {
            preparedVideoFiles.forEach(mediaStorageService::deleteQuietly);
        }
    }
}
