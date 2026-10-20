package com.gamerin.backend.domain.post.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;

/**
 * soft delete된 글과 숨긴 댓글을 보존 기간 뒤 영구 삭제한다.
 * 신고 접수·관리자 판정·복구와 같은 잠금 순서(report_counts → posts → post_comments)를 지키고,
 * 잠근 뒤 조건을 다시 확인해 그 사이 복구된 콘텐츠는 건너뛴다.
 */
@Service
public class PostCleanupService {

    private final PostRepository postRepository;
    private final PostCommentRepository postCommentRepository;
    private final PostMediaRepository postMediaRepository;
    private final ReportCountRepository reportCountRepository;
    private final MediaStorageService mediaStorageService;
    private final Duration hardDeleteAfter;
    private final Duration hiddenHardDeleteAfter;

    public PostCleanupService(
            PostRepository postRepository,
            PostCommentRepository postCommentRepository,
            PostMediaRepository postMediaRepository,
            ReportCountRepository reportCountRepository,
            MediaStorageService mediaStorageService,
            @Value("${app.post.cleanup.hard-delete-after:24h}") Duration hardDeleteAfter,
            @Value("${app.post.cleanup.hidden-hard-delete-after:14d}") Duration hiddenHardDeleteAfter
    ) {
        this.postRepository = postRepository;
        this.postCommentRepository = postCommentRepository;
        this.postMediaRepository = postMediaRepository;
        this.reportCountRepository = reportCountRepository;
        this.mediaStorageService = mediaStorageService;
        this.hardDeleteAfter = hardDeleteAfter;
        this.hiddenHardDeleteAfter = hiddenHardDeleteAfter;
    }

    @Scheduled(
            initialDelayString = "${app.post.cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.post.cleanup.fixed-delay-ms:3600000}"
    )
    @Transactional
    public void purgeExpiredSoftDeletedPosts() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(hardDeleteAfter);
        OffsetDateTime hiddenCutoff = now.minus(hiddenHardDeleteAfter);

        for (UUID commentId : postCommentRepository.findHiddenIdsBefore(hiddenCutoff)) {
            purgeHiddenComment(commentId, hiddenCutoff);
        }

        OffsetDateTime candidateCutoff = cutoff.isAfter(hiddenCutoff) ? cutoff : hiddenCutoff;
        for (UUID postId : postRepository.findSoftDeletedIdsBefore(candidateCutoff)) {
            purgePost(postId, cutoff, hiddenCutoff);
        }
    }

    private void purgeHiddenComment(UUID commentId, OffsetDateTime hiddenCutoff) {
        List<UUID> commentIds = List.of(commentId);
        reportCountRepository.lockByTargets(ReportTargetType.COMMENT, commentIds);
        postCommentRepository.findHiddenHardDeleteCandidateForUpdate(commentId, hiddenCutoff)
                .ifPresent(comment -> {
                    reportCountRepository.deleteByTargets(ReportTargetType.COMMENT, commentIds);
                    // 알림·멘션은 FK ON DELETE CASCADE로 함께 삭제된다. 숨김 시 댓글 수에서 이미 빠졌다.
                    postCommentRepository.delete(comment);
                });
    }

    private void purgePost(UUID postId, OffsetDateTime cutoff, OffsetDateTime hiddenCutoff) {
        List<UUID> postIds = List.of(postId);
        List<UUID> commentIds = postCommentRepository.findIdsByPostId(postId);
        reportCountRepository.lockByTargets(ReportTargetType.POST, postIds);
        if (!commentIds.isEmpty()) {
            reportCountRepository.lockByTargets(ReportTargetType.COMMENT, commentIds);
        }

        postRepository.findHardDeleteCandidateForUpdate(postId, cutoff, hiddenCutoff)
                .ifPresent(post -> hardDelete(post, commentIds));
    }

    private void hardDelete(Post post, List<UUID> commentIds) {
        List<PostMedia> mediaList = postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(post.getId());

        // 댓글은 글을 따라 함께 삭제한다. 댓글의 알림·멘션은 FK ON DELETE CASCADE로 지워진다.
        reportCountRepository.deleteByTargets(ReportTargetType.POST, List.of(post.getId()));
        if (!commentIds.isEmpty()) {
            reportCountRepository.deleteByTargets(ReportTargetType.COMMENT, commentIds);
            postCommentRepository.deleteAllByIdInBatch(commentIds);
        }
        postRepository.delete(post);

        for (PostMedia media : mediaList) {
            mediaStorageService.deletePublicUrlQuietly(media.getMediaUrl());
            mediaStorageService.deletePublicUrlQuietly(media.getThumbnailUrl());
        }
    }
}
