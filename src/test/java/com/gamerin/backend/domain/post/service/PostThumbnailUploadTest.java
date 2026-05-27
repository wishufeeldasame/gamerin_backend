package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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

import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
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
class PostThumbnailUploadTest {

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
    private MediaUploadSecurityService mediaUploadSecurityService;

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
                mediaUploadSecurityService
        );
    }

    @Test
    void createMultipartStoresSelectedThumbnailForVideo() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });
        when(postResponseAssembler.toPostDetail(any(Post.class), any(UUID.class))).thenReturn(postDetailResponse(postId));
        when(videoMetadataService.readDurationSeconds(any())).thenReturn(90.0);
        when(mediaUploadSecurityService.prepareImage(any()))
                .thenReturn(new MediaStorageService.PreparedMediaFile("thumbnail".getBytes(), ".jpg"));
        when(mediaStorageService.storePostMedia(any(MediaStorageService.PreparedMediaFile.class)))
                .thenReturn(new MediaStorageService.StoredFile(
                        Path.of("uploads/post-media/thumb.jpg"),
                        "http://localhost:8080/uploads/post-media/thumb.jpg"
                ));
        when(mediaStorageService.storePostMedia(any(MultipartFile.class)))
                .thenReturn(new MediaStorageService.StoredFile(
                        Path.of("uploads/post-media/video.mp4"),
                        "http://localhost:8080/uploads/post-media/video.mp4"
                ));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("video post");
        request.setMediaFiles(List.of(videoFile()));
        request.setThumbnailFile(thumbnailFile());

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        assertThat(savedMedia).hasSize(1);
        assertThat(savedMedia.getFirst().getMediaType()).isEqualTo(PostMediaType.VIDEO);
        assertThat(savedMedia.getFirst().getMediaUrl()).endsWith("/video.mp4");
        assertThat(savedMedia.getFirst().getThumbnailUrl()).endsWith("/thumb.jpg");
        verify(mediaStorageService, never()).deleteQuietly(any());
    }

    @Test
    void createMultipartAllowsVideoWithoutThumbnail() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            ReflectionTestUtils.setField(post, "id", postId);
            return post;
        });
        when(postResponseAssembler.toPostDetail(any(Post.class), any(UUID.class))).thenReturn(postDetailResponse(postId));
        when(videoMetadataService.readDurationSeconds(any())).thenReturn(90.0);
        when(mediaStorageService.storePostMedia(any(MultipartFile.class)))
                .thenReturn(new MediaStorageService.StoredFile(
                        Path.of("uploads/post-media/video.mp4"),
                        "http://localhost:8080/uploads/post-media/video.mp4"
                ));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile()));

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        assertThat(mediaCaptor.getValue()).hasSize(1);
        assertThat(mediaCaptor.getValue().getFirst().getThumbnailUrl()).isNull();
    }

    @Test
    void createMultipartRejectsThumbnailForImagePost() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(imageFile()));
        request.setThumbnailFile(thumbnailFile());

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createMultipartRejectsNonImageThumbnailForVideoPost() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId);

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile()));
        request.setThumbnailFile(new MockMultipartFile(
                "thumbnailFile",
                "thumbnail.txt",
                "text/plain",
                "not image".getBytes()
        ));

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
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

    private User savedUser(UUID id) {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private MockMultipartFile imageFile() {
        return new MockMultipartFile("mediaFiles", "image.jpg", "image/jpeg", "image".getBytes());
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile("mediaFiles", "video.mp4", "video/mp4", "video".getBytes());
    }

    private MockMultipartFile thumbnailFile() {
        return new MockMultipartFile("thumbnailFile", "thumb.jpg", "image/jpeg", "thumbnail".getBytes());
    }
}
