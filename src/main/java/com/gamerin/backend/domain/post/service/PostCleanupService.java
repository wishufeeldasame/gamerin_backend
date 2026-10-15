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
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;

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
        OffsetDateTime hiddenCutoff = now.minus(hiddenHardDeleteAfter);

        purgeHiddenComments(hiddenCutoff);

        List<Post> posts = postRepository.findHardDeleteCandidates(now.minus(hardDeleteAfter), hiddenCutoff);
        for (Post post : posts) {
            hardDelete(post);
        }
    }

    private void purgeHiddenComments(OffsetDateTime hiddenCutoff) {
        List<PostComment> comments = postCommentRepository.findHiddenHardDeleteCandidates(hiddenCutoff);
        if (comments.isEmpty()) {
            return;
        }
        reportCountRepository.deleteByTargets(ReportTargetType.COMMENT,
                comments.stream().map(PostComment::getId).toList());
        // 알림·멘션은 FK ON DELETE CASCADE로 함께 삭제된다. 숨김 시 댓글 수에서 이미 빠졌다.
        postCommentRepository.deleteAllInBatch(comments);
    }

    private void hardDelete(Post post) {
        List<PostMedia> mediaList = postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(post.getId());
        List<UUID> commentIds = postCommentRepository.findIdsByPostId(post.getId());

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
