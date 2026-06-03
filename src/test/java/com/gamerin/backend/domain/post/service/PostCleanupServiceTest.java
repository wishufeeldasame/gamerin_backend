package com.gamerin.backend.domain.post.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;

@ExtendWith(MockitoExtension.class)
class PostCleanupServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private MediaStorageService mediaStorageService;

    private PostCleanupService postCleanupService;

    @BeforeEach
    void setUp() {
        postCleanupService = new PostCleanupService(
                postRepository,
                postMediaRepository,
                mediaStorageService,
                Duration.ofDays(1)
        );
    }

    @Test
    void purgeExpiredSoftDeletedPostsHardDeletesPostAndMediaFiles() {
        UUID postId = UUID.randomUUID();
        User author = savedUser();
        Post post = Post.create(author, "post");
        ReflectionTestUtils.setField(post, "id", postId);
        ReflectionTestUtils.setField(post, "deletedAt", OffsetDateTime.now().minusDays(2));

        PostMedia media = PostMedia.create(
                post,
                PostMediaType.VIDEO,
                "/uploads/post-media/video.mp4",
                "/uploads/post-media/thumb.jpg",
                0
        );

        when(postRepository.findHardDeleteCandidates(any(OffsetDateTime.class))).thenReturn(List.of(post));
        when(postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(postId)).thenReturn(List.of(media));

        postCleanupService.purgeExpiredSoftDeletedPosts();

        verify(postRepository).delete(post);
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/video.mp4");
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/thumb.jpg");
    }

    private User savedUser() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
