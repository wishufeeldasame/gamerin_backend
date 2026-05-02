package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.dto.request.CreatePostMediaRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
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
    private PostLikeRepository postLikeRepository;

    @Mock
    private PostCommentRepository postCommentRepository;

    @Mock
    private PostResponseAssembler postResponseAssembler;

    private PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                userRepository,
                postRepository,
                postMediaRepository,
                postLikeRepository,
                postCommentRepository,
                postResponseAssembler
        );
    }

    @Test
    void createRejectsWhenContentAndMediaAreMissing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> postService.create(
                CustomUserPrincipal.from(user),
                new CreatePostRequest("   ", null, List.of())
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createRejectsMixedImageAndVideoMedia() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        CreatePostRequest request = new CreatePostRequest(
                null,
                "PUBG",
                List.of(
                        new CreatePostMediaRequest(PostMediaType.IMAGE, "https://cdn.example.com/image.png", null, 0, null),
                        new CreatePostMediaRequest(PostMediaType.VIDEO, "https://cdn.example.com/video.mp4", "https://cdn.example.com/video.jpg", 1, 12)
                )
        );

        assertThatThrownBy(() -> postService.create(CustomUserPrincipal.from(user), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    void createStoresVideoMediaForVideoOnlyPost() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");
        PostDetailResponse response = new PostDetailResponse(
                postId,
                "Tester",
                "tester",
                null,
                false,
                "GENERAL",
                null,
                List.of(),
                0,
                0,
                0,
                false,
                true,
                null
        );

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
                ))
        );

        postService.create(CustomUserPrincipal.from(user), request);

        ArgumentCaptor<List<PostMedia>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(postMediaRepository).saveAll(mediaCaptor.capture());

        List<PostMedia> savedMedia = mediaCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(savedMedia).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(savedMedia.getFirst().getMediaType()).isEqualTo(PostMediaType.VIDEO);
        org.assertj.core.api.Assertions.assertThat(savedMedia.getFirst().getThumbnailUrl())
                .isEqualTo("https://cdn.example.com/video.jpg");
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
