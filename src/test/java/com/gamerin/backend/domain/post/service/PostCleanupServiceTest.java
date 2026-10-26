package com.gamerin.backend.domain.post.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Mock
    private PlatformTransactionManager transactionManager;

    private PostCleanupService postCleanupService;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        postCleanupService = new PostCleanupService(
                postRepository,
                postCommentRepository,
                postMediaRepository,
                reportCountRepository,
                mediaStorageService,
                new TransactionTemplate(transactionManager),
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
        // 파일은 커밋 뒤에 지운다.
        InOrder commitThenDelete = inOrder(transactionManager, mediaStorageService);
        commitThenDelete.verify(transactionManager).commit(any(TransactionStatus.class));
        commitThenDelete.verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/video.mp4");
        commitThenDelete.verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/thumb.jpg");
    }

    @Test
    void purgeKeepsMediaFilesOfPostWhoseCommitFailsAndContinuesWithNextPost() {
        Post first = expiredPostWithMedia("first");
        Post failing = expiredPostWithMedia("failing");
        Post last = expiredPostWithMedia("last");
        when(postRepository.findSoftDeletedIdsBefore(any(OffsetDateTime.class)))
                .thenReturn(List.of(first.getId(), failing.getId(), last.getId()));
        // 삭제 SQL은 커밋 시 flush되므로 FK 위반은 커밋에서 난다.
        doNothing()
                .doThrow(new DataIntegrityViolationException("fk violation"))
                .doNothing()
                .when(transactionManager).commit(any(TransactionStatus.class));

        postCleanupService.purgeExpiredSoftDeletedPosts();

        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/first.mp4");
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/first.jpg");
        verify(mediaStorageService, never()).deletePublicUrlQuietly("/uploads/post-media/failing.mp4");
        verify(mediaStorageService, never()).deletePublicUrlQuietly("/uploads/post-media/failing.jpg");
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/last.mp4");
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/last.jpg");
    }

    @Test
    void purgeRollsBackFailingItemsAndProcessesTheRest() {
        UUID failingCommentId = UUID.randomUUID();
        when(postCommentRepository.findHiddenIdsBefore(any(OffsetDateTime.class))).thenReturn(List.of(failingCommentId));
        when(postCommentRepository.findHiddenHardDeleteCandidateForUpdate(eq(failingCommentId), any(OffsetDateTime.class)))
                .thenThrow(new IllegalStateException("lock failed"));

        Post failing = expiredPostWithMedia("failing");
        Post last = expiredPostWithMedia("last");
        when(postRepository.findSoftDeletedIdsBefore(any(OffsetDateTime.class)))
                .thenReturn(List.of(failing.getId(), last.getId()));
        doThrow(new IllegalStateException("delete failed")).when(postRepository).delete(failing);

        postCleanupService.purgeExpiredSoftDeletedPosts();

        // 실패한 댓글·글은 각각 롤백되고, 남은 글은 커밋된다.
        verify(transactionManager, times(2)).rollback(any(TransactionStatus.class));
        verify(transactionManager).commit(any(TransactionStatus.class));
        verify(postRepository).delete(last);
        verify(mediaStorageService, never()).deletePublicUrlQuietly(contains("failing"));
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/last.mp4");
        verify(mediaStorageService).deletePublicUrlQuietly("/uploads/post-media/last.jpg");
    }

    private Post expiredPostWithMedia(String name) {
        UUID postId = UUID.randomUUID();
        Post post = Post.create(savedUser(), name);
        ReflectionTestUtils.setField(post, "id", postId);
        ReflectionTestUtils.setField(post, "deletedAt", OffsetDateTime.now().minusDays(2));
        PostMedia media = PostMedia.create(
                post,
                PostMediaType.VIDEO,
                "/uploads/post-media/" + name + ".mp4",
                "/uploads/post-media/" + name + ".jpg",
                0
        );
        when(postRepository.findHardDeleteCandidateForUpdate(eq(postId), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).thenReturn(Optional.of(post));
        when(postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(postId)).thenReturn(List.of(media));
        return post;
    }

    private User savedUser() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
