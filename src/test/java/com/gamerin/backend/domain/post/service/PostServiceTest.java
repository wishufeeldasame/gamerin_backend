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
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateExternalLinkRequest;
import com.gamerin.backend.domain.post.dto.request.CreateMultipartPostRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostMediaRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostExternalLink;
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

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private PostExternalLinkRepository postExternalLinkRepository;

    @Mock
    private PostLikeRepository postLikeRepository;

    @Mock
    private PostCommentRepository postCommentRepository;

    @Mock
    private PostResponseAssembler postResponseAssembler;

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private ExternalLinkMetadataService externalLinkMetadataService;

    private PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                userRepository,
                postRepository,
                postMediaRepository,
                postExternalLinkRepository,
                postLikeRepository,
                postCommentRepository,
                postResponseAssembler,
                mediaStorageService,
                externalLinkMetadataService
        );
    }

    @Test
    void createRejectsWhenContentMediaAndExternalLinkAreMissing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> postService.create(
                CustomUserPrincipal.from(user),
                new CreatePostRequest("   ", null, List.of(), null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createRejectsWhenMediaAndExternalLinkAreUsedTogether() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreatePostRequest request = new CreatePostRequest(
                "post",
                "PUBG",
                List.of(new CreatePostMediaRequest(PostMediaType.IMAGE, "https://cdn.example.com/image.png", null, 0, null)),
                new CreateExternalLinkRequest("https://youtube.com/watch?v=test")
        );

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void createStoresExternalLinkForLinkOnlyPost() {
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
        when(externalLinkMetadataService.fetch("https://youtu.be/abc123"))
                .thenReturn(new ExternalLinkMetadataService.ExternalLinkMetadata(
                        "https://youtu.be/abc123",
                        "youtu.be",
                        "YouTube",
                        "https://youtu.be/abc123",
                        "https://img.youtube.com/vi/abc123/hqdefault.jpg"
                ));

        CreatePostRequest request = new CreatePostRequest(
                null,
                "PUBG",
                List.of(),
                new CreateExternalLinkRequest("https://youtu.be/abc123")
        );

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<PostExternalLink> linkCaptor = ArgumentCaptor.forClass(PostExternalLink.class);
        verify(postExternalLinkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getOriginalUrl()).isEqualTo("https://youtu.be/abc123");
        assertThat(linkCaptor.getValue().getHost()).isEqualTo("youtu.be");
    }

    @Test
    void createStoresVideoMediaForVideoOnlyPost() {
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

        CreatePostRequest request = new CreatePostRequest(
                null,
                null,
                List.of(new CreatePostMediaRequest(
                        PostMediaType.VIDEO,
                        "https://cdn.example.com/video.mp4",
                        "https://cdn.example.com/video.jpg",
                        0,
                        30
                )),
                null
        );

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        assertThat(savedMedia).hasSize(1);
        assertThat(savedMedia.getFirst().getMediaType()).isEqualTo(PostMediaType.VIDEO);
        assertThat(savedMedia.getFirst().getThumbnailUrl()).isEqualTo("https://cdn.example.com/video.jpg");
    }

    @Test
    void createMultipartRejectsVideoWithoutThumbnail() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setMediaFiles(List.of(videoFile()));
        request.setDurationSeconds(30);

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createMultipartRejectsMediaAndExternalLinkTogether() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("post");
        request.setMediaFiles(List.of(imageFile("a.jpg")));
        request.setExternalLinkUrl("https://youtube.com/watch?v=test");

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
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
        when(mediaStorageService.storePostMedia(any()))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("uploads/post-media/a.jpg"), "http://localhost:8080/uploads/post-media/a.jpg"))
                .thenReturn(new MediaStorageService.StoredFile(Path.of("uploads/post-media/b.jpg"), "http://localhost:8080/uploads/post-media/b.jpg"));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("image post");
        request.setGameName("PUBG");
        request.setMediaFiles(List.of(imageFile("a.jpg"), imageFile("b.jpg")));

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        assertThat(savedMedia).hasSize(2);
        assertThat(savedMedia.get(0).getMediaType()).isEqualTo(PostMediaType.IMAGE);
        assertThat(savedMedia.get(0).getMediaUrl()).endsWith("/a.jpg");
        assertThat(savedMedia.get(1).getMediaUrl()).endsWith("/b.jpg");
        verify(mediaStorageService, never()).deleteQuietly(any());
    }

    @Test
    void createMultipartStoresExternalLinkForLinkOnlyPost() {
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
        when(externalLinkMetadataService.fetch("https://example.com/article"))
                .thenReturn(new ExternalLinkMetadataService.ExternalLinkMetadata(
                        "https://example.com/article",
                        "example.com",
                        "example.com",
                        "https://example.com/article",
                        null
                ));

        CreateMultipartPostRequest request = new CreateMultipartPostRequest();
        request.setContent("link post");
        request.setExternalLinkUrl("https://example.com/article");

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<PostExternalLink> linkCaptor = ArgumentCaptor.forClass(PostExternalLink.class);
        verify(postExternalLinkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getOriginalUrl()).isEqualTo("https://example.com/article");
    }

    private PostDetailResponse postDetailResponse(UUID postId) {
        return new PostDetailResponse(
                postId,
                "Tester",
                "tester",
                null,
                false,
                "GENERAL",
                null,
                List.of(),
                null,
                0,
                0,
                0,
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

    private MockMultipartFile imageFile(String name) {
        return new MockMultipartFile("mediaFiles", name, "image/jpeg", "image".getBytes());
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile("mediaFiles", "video.mp4", "video/mp4", "video".getBytes());
    }
}
