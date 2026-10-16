package com.gamerin.backend.domain.post.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.user.entity.User;

@ExtendWith(MockitoExtension.class)
class PostCleanupServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostCommentRepository postCommentRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private ReportCountRepository reportCountRepository;

    @Mock
    private MediaStorageService mediaStorageService;

    private PostCleanupService postCleanupService;

    @BeforeEach
    void setUp() {
        postCleanupService = new PostCleanupService(
                postRepository,
                postCommentRepository,
                postMediaRepository,
                reportCountRepository,
                mediaStorageService,
                Duration.ofDays(1),
                Duration.ofDays(14)
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

        UUID commentId = UUID.randomUUID();
        when(postRepository.findSoftDeletedIdsBefore(any(OffsetDateTime.class))).thenReturn(List.of(postId));
        when(postRepository.findHardDeleteCandidateForUpdate(eq(postId), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).thenReturn(Optional.of(post));
        when(postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(postId)).thenReturn(List.of(media));
        when(postCommentRepository.findIdsByPostId(postId)).thenReturn(List.of(commentId));

        postCleanupService.purgeExpiredSoftDeletedPosts();

        InOrder lockOrder = inOrder(reportCountRepository, postRepository);
        lockOrder.verify(reportCountRepository).lockByTargets(ReportTargetType.POST, List.of(postId));
        lockOrder.verify(reportCountRepository).lockByTargets(ReportTargetType.COMMENT, List.of(commentId));
        lockOrder.verify(postRepository).findHardDeleteCandidateForUpdate(eq(postId), any(OffsetDateTime.class),
                any(OffsetDateTime.class));
        verify(reportCountRepository).deleteByTargets(ReportTargetType.POST, List.of(postId));
        verify(reportCountRepository).deleteByTargets(ReportTargetType.COMMENT, List.of(commentId));
        verify(postCommentRepository).deleteAllByIdInBatch(List.of(commentId));
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
