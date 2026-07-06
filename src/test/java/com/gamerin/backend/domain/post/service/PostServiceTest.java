package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.dto.response.CommentResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.entity.PostShare;
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

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    private static final long TEST_MAX_VIDEO_FILE_SIZE_BYTES = 500L * 1024L * 1024L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private PostLikeRepository postLikeRepository;

    @Mock
    private PostBookmarkRepository postBookmarkRepository;

    @Mock
    private PostCommentRepository postCommentRepository;

    @Mock
    private PostShareRepository postShareRepository;

    @Mock
    private PostResponseAssembler postResponseAssembler;

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private VideoMetadataService videoMetadataService;

    @Mock
    private ContentModerationService contentModerationService;

    @Mock
    private MediaUploadSecurityService mediaUploadSecurityService;

    @Mock
    private LightweightSecurityScanService lightweightSecurityScanService;

    @Mock
    private TextSecurityService textSecurityService;

    @Mock
    private VideoOptimizationService videoOptimizationService;

    private PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                userRepository,
                postRepository,
                postMediaRepository,
                postLikeRepository,
                postBookmarkRepository,
                postCommentRepository,
                postShareRepository,
                postResponseAssembler,
                mediaStorageService,
                videoMetadataService,
                contentModerationService,
                mediaUploadSecurityService,
                lightweightSecurityScanService,
                textSecurityService,
                videoOptimizationService,
                TEST_MAX_VIDEO_FILE_SIZE_BYTES
        );
    }

    @Test
    void constructorRejectsInvalidVideoSizeConfiguration() {
        assertThatThrownBy(() -> new PostService(
                userRepository,
                postRepository,
                postMediaRepository,
                postLikeRepository,
                postBookmarkRepository,
                postCommentRepository,
                postShareRepository,
                postResponseAssembler,
                mediaStorageService,
                videoMetadataService,
                contentModerationService,
                mediaUploadSecurityService,
                lightweightSecurityScanService,
                textSecurityService,
                videoOptimizationService,
                0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max file size");
    }

    @Test
    void createRejectsWhenContentIsMissing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> postService.create(
                CustomUserPrincipal.from(user),
                new CreatePostRequest("   ")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createRejectsWhenTextModerationFlagsContent() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Content violates moderation policy: violence"))
                .when(contentModerationService)
                .assertTextAllowed("bad post");

        assertThatThrownBy(() -> postService.create(
                CustomUserPrincipal.from(user),
                new CreatePostRequest(" bad post ")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createMultipartStoresUploadedVideoWithoutThumbnail() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        PostDetailResponse response = postDetailResponse(postId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });
        when(postResponseAssembler.toPostDetail(any(Post.class), any(UUID.class))).thenReturn(response);
        when(videoMetadataService.readDurationSeconds(any())).thenReturn(120.0);
        MediaStorageService.PreparedMediaPath preparedVideo = preparedVideo();
        when(videoOptimizationService.prepareVideo(any(MultipartFile.class))).thenReturn(preparedVideo);
        when(mediaStorageService.storePostMedia(any(MediaStorageService.PreparedMediaPath.class)))
                .thenReturn(new MediaStorageService.StoredFile(
                        Path.of("uploads/post-media/video.mp4"),
                        "http://localhost:8080/uploads/post-media/video.mp4"
                ));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile()));

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        assertThat(savedMedia).hasSize(1);
        assertThat(savedMedia.get(0).getMediaType()).isEqualTo(PostMediaType.VIDEO);
        assertThat(savedMedia.get(0).getMediaUrl()).endsWith("/video.mp4");
        assertThat(savedMedia.get(0).getThumbnailUrl()).isNull();
        verify(mediaStorageService).deleteQuietly(preparedVideo);
        verify(mediaStorageService, never()).deleteQuietly(any(MediaStorageService.StoredFile.class));
    }

    @Test
    void createMultipartRejectsVideoLargerThanConfiguredLimit() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        MultipartFile videoFile = mock(MultipartFile.class);
        when(videoFile.isEmpty()).thenReturn(false);
        when(videoFile.getContentType()).thenReturn("video/mp4");
        when(videoFile.getSize()).thenReturn(TEST_MAX_VIDEO_FILE_SIZE_BYTES + 1L);

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                    assertThat(exception.getReason()).isEqualTo("Video file exceeds the configured size limit.");
                });

        verify(postRepository, never()).save(any(Post.class));
        verify(mediaUploadSecurityService, never()).assertVideoFileSafe(videoFile);
        verify(lightweightSecurityScanService, never()).assertFileClean(videoFile);
        verify(videoMetadataService, never()).readDurationSeconds(videoFile);
    }

    @Test
    void createMultipartAcceptsVideoAtConfiguredSizeBoundary() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        MultipartFile videoFile = mock(MultipartFile.class);
        when(videoFile.isEmpty()).thenReturn(false);
        when(videoFile.getContentType()).thenReturn("video/mp4");
        when(videoFile.getSize()).thenReturn(TEST_MAX_VIDEO_FILE_SIZE_BYTES);
        when(videoMetadataService.readDurationSeconds(videoFile)).thenReturn(120.01);

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("Video duration must be 2 minutes or shorter.");
                });

        verify(mediaUploadSecurityService).assertVideoFileSafe(videoFile);
        verify(lightweightSecurityScanService).assertFileClean(videoFile);
        verify(videoMetadataService).readDurationSeconds(videoFile);
    }

    @Test
    void createMultipartRejectsMoreThanOneVideoBeforeMediaProcessing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        MultipartFile firstVideo = mockVideoFile();
        MultipartFile secondVideo = mockVideoFile();

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(firstVideo, secondVideo));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("You can upload only one video");

        verify(mediaUploadSecurityService, never()).assertVideoFileSafe(any(MultipartFile.class));
        verify(videoMetadataService, never()).readDurationSeconds(any(MultipartFile.class));
        verify(videoOptimizationService, never()).prepareVideo(any(MultipartFile.class));
    }

    @Test
    void createMultipartRejectsMixedImageAndVideoBeforeVideoProcessing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        MockMultipartFile image = imageFile("image.jpg");
        MultipartFile video = mockVideoFile();

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(image, video));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Images and videos cannot be uploaded together");

        verify(mediaUploadSecurityService).assertImageFileSafe(image);
        verify(mediaUploadSecurityService, never()).assertVideoFileSafe(video);
        verify(videoMetadataService, never()).readDurationSeconds(video);
        verify(videoOptimizationService, never()).prepareVideo(video);
    }

    @Test
    void createMultipartRejectsVideoLongerThanTwoMinutes() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(videoMetadataService.readDurationSeconds(any())).thenReturn(121.0);

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile()));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createMultipartRejectsWhenModerationFlagsMediaBeforeStorage() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Content violates moderation policy: sexual"))
                .when(contentModerationService)
                .assertPostAllowed(eq("image post"), anyList());

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent(" image post ");
        request.setMediaFiles(List.of(imageFile("a.jpg")));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(postRepository, never()).save(any(Post.class));
        verify(mediaStorageService, never()).storePostMedia(any(MultipartFile.class));
        verify(mediaStorageService, never()).storePostMedia(any(MediaStorageService.PreparedMediaFile.class));
        verify(mediaStorageService, never()).storePostMedia(any(MediaStorageService.PreparedMediaPath.class));
    }

    @Test
    void createMultipartStoresUploadedImages() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        PostDetailResponse response = postDetailResponse(postId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });
        when(postResponseAssembler.toPostDetail(any(Post.class), any(UUID.class))).thenReturn(response);
        when(mediaUploadSecurityService.prepareImage(any(MultipartFile.class)))
                .thenReturn(preparedImage())
                .thenReturn(preparedImage());
        when(mediaStorageService.storePostMedia(any(MediaStorageService.PreparedMediaFile.class)))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("uploads/post-media/a.jpg"), "http://localhost:8080/uploads/post-media/a.jpg"))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("uploads/post-media/b.jpg"), "http://localhost:8080/uploads/post-media/b.jpg"));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("image post");
        request.setMediaFiles(List.of(imageFile("a.jpg"), imageFile("b.jpg")));

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        assertThat(savedMedia).hasSize(2);
        assertThat(savedMedia.get(0).getMediaType()).isEqualTo(PostMediaType.IMAGE);
        assertThat(savedMedia.get(0).getMediaUrl()).endsWith("/a.jpg");
        assertThat(savedMedia.get(1).getMediaUrl()).endsWith("/b.jpg");
        verify(mediaStorageService, never()).deleteQuietly(any(MediaStorageService.StoredFile.class));
        verify(mediaStorageService, never()).deleteQuietly(any(MediaStorageService.PreparedMediaPath.class));
    }

    @Test
    void createMultipartStoresSanitizedAnimatedGifAsImage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "gif-user", "GIF User");
        PostDetailResponse response = postDetailResponse(postId);
        MockMultipartFile gif = new MockMultipartFile(
                "mediaFiles",
                "animation.gif",
                "image/gif",
                "GIF89a".getBytes()
        );
        MediaStorageService.PreparedMediaFile preparedGif =
                new MediaStorageService.PreparedMediaFile("sanitized-animation".getBytes(), ".gif");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });
        when(postResponseAssembler.toPostDetail(any(Post.class), any(UUID.class))).thenReturn(response);
        when(mediaUploadSecurityService.prepareImage(gif)).thenReturn(preparedGif);
        when(mediaStorageService.storePostMedia(preparedGif)).thenReturn(new MediaStorageService.StoredFile(
                Path.of("uploads/post-media/animation.gif"),
                "http://localhost:8080/uploads/post-media/animation.gif"
        ));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("animated gif post");
        request.setMediaFiles(List.of(gif));

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());
        assertThat(mediaCaptor.getValue()).singleElement().satisfies(media -> {
            assertThat(media.getMediaType()).isEqualTo(PostMediaType.IMAGE);
            assertThat(media.getMediaUrl()).endsWith("/animation.gif");
        });
        verify(mediaUploadSecurityService).assertImageFileSafe(gif);
        verify(mediaUploadSecurityService).prepareImage(gif);
    }

    @Test
    void bookmarkDoesNothingWhenAlreadyBookmarked() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postBookmarkRepository.existsByPostIdAndUserId(postId, userId)).thenReturn(true);

        postService.bookmark(CustomUserPrincipal.from(user), postId);

        verify(postBookmarkRepository, never()).save(any(PostBookmark.class));
    }

    @Test
    void bookmarkStoresWhenMissing() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postBookmarkRepository.existsByPostIdAndUserId(postId, userId)).thenReturn(false);

        postService.bookmark(CustomUserPrincipal.from(user), postId);

        verify(postBookmarkRepository).save(any(PostBookmark.class));
    }

    @Test
    void deleteSoftDeletesOwnPost() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        postService.delete(CustomUserPrincipal.from(user), postId);

        assertThat(post.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteRejectsWhenUserIsNotAuthor() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        User author = savedUser(otherUserId, "author", "Author");
        Post post = savedPost(postId, author);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.delete(CustomUserPrincipal.from(user), postId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(post.getDeletedAt()).isNull();
    }

    @Test
    void shareStoresEventAndIncreasesShareCount() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));

        postService.share(CustomUserPrincipal.from(user), postId, new CreateShareRequest(ShareTarget.KAKAO));

        verify(postShareRepository).save(any(PostShare.class));
        assertThat(post.getShareCount()).isEqualTo(1);
    }

    @Test
    void createCommentRejectsWhenTextModerationFlagsContent() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Content violates moderation policy: harassment"))
                .when(contentModerationService)
                .assertTextAllowed("bad comment");

        assertThatThrownBy(() -> postService.createComment(
                CustomUserPrincipal.from(user),
                postId,
                new CreateCommentRequest(" bad comment ")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());

        verify(postCommentRepository, never()).save(any());
    }

    @Test
    void getCommentsReturnsActiveComments() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);
        PostComment comment = PostComment.create(post, user, "hello");
        CommentResponse response = new CommentResponse(
                commentId,
                "Tester",
                "tester",
                null,
                false,
                "hello",
                OffsetDateTime.now(),
                true
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.findByIdAndDeletedAtIsNull(postId)).thenReturn(Optional.of(post));
        when(postCommentRepository.findActiveByPostId(postId)).thenReturn(List.of(comment));
        when(postResponseAssembler.toCommentResponse(comment, userId)).thenReturn(response);

        List<CommentResponse> comments = postService.getComments(CustomUserPrincipal.from(user), postId);

        assertThat(comments).containsExactly(response);
    }

    @Test
    void deleteCommentHardDeletesOwnCommentAndDecreasesCommentCount() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        Post post = savedPost(postId, user);
        post.increaseCommentCount();
        PostComment comment = PostComment.create(post, user, "hello");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postCommentRepository.findActiveByPostIdAndId(postId, commentId)).thenReturn(Optional.of(comment));

        postService.deleteComment(CustomUserPrincipal.from(user), postId, commentId);

        verify(postCommentRepository).delete(comment);
        assertThat(post.getCommentCount()).isZero();
    }

    @Test
    void deleteCommentRejectsWhenUserIsNotAuthor() {
        UUID userId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        User author = savedUser(authorId, "author", "Author");
        Post post = savedPost(postId, author);
        PostComment comment = PostComment.create(post, author, "hello");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postCommentRepository.findActiveByPostIdAndId(postId, commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> postService.deleteComment(CustomUserPrincipal.from(user), postId, commentId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        assertThat(comment.getDeletedAt()).isNull();
    }

    private PostDetailResponse postDetailResponse(UUID postId) {
        return new PostDetailResponse(
                postId,
                "Tester",
                "tester",
                null,
                false,
                null,
                List.of(),
                0,
                0,
                0,
                false,
                false,
                true,
                null
        );
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Post savedPost(UUID id, User author) {
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private MockMultipartFile imageFile(String name) {
        return new MockMultipartFile("mediaFiles", name, "image/jpeg", "image".getBytes());
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile("mediaFiles", "video.mp4", "video/mp4", "video".getBytes());
    }

    private MultipartFile mockVideoFile() {
        MultipartFile videoFile = mock(MultipartFile.class);
        when(videoFile.isEmpty()).thenReturn(false);
        when(videoFile.getContentType()).thenReturn("video/mp4");
        return videoFile;
    }

    private MediaStorageService.PreparedMediaFile preparedImage() {
        return new MediaStorageService.PreparedMediaFile("compressed-image".getBytes(), ".jpg");
    }

    private MediaStorageService.PreparedMediaPath preparedVideo() {
        return new MediaStorageService.PreparedMediaPath(Path.of("tmp/optimized-video.mp4"), ".mp4");
    }
}
