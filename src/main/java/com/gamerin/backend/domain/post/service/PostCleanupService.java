package com.gamerin.backend.domain.post.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;

@Service
public class PostCleanupService {

    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final MediaStorageService mediaStorageService;
    private final Duration hardDeleteAfter;

    public PostCleanupService(
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            MediaStorageService mediaStorageService,
            @Value("${app.post.cleanup.hard-delete-after:24h}") Duration hardDeleteAfter
    ) {
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.mediaStorageService = mediaStorageService;
        this.hardDeleteAfter = hardDeleteAfter;
    }

    @Scheduled(
            initialDelayString = "${app.post.cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.post.cleanup.fixed-delay-ms:3600000}"
    )
    @Transactional
    public void purgeExpiredSoftDeletedPosts() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(hardDeleteAfter);
        List<Post> posts = postRepository.findHardDeleteCandidates(cutoff);

        for (Post post : posts) {
            hardDelete(post);
        }
    }

    private void hardDelete(Post post) {
        List<PostMedia> mediaList = postMediaRepository.findByPostIdOrderBySortOrderAscIdAsc(post.getId());

        postRepository.delete(post);

        for (PostMedia media : mediaList) {
            mediaStorageService.deletePublicUrlQuietly(media.getMediaUrl());
            mediaStorageService.deletePublicUrlQuietly(media.getThumbnailUrl());
        }
    }
}
