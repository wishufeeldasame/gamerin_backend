package com.gamerin.backend.domain.hashtag.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagBackfillResult;
import com.gamerin.backend.domain.hashtag.model.HashtagBackfillCursor;
import com.gamerin.backend.domain.hashtag.repository.HashtagBackfillRepository;
import com.gamerin.backend.domain.post.entity.Post;

@Service
public class HashtagBackfillService {

    private static final int MAX_BATCH_SIZE = 1000;

    private final HashtagBackfillRepository hashtagBackfillRepository;
    private final HashtagService hashtagService;
    private final TransactionTemplate transactionTemplate;

    public HashtagBackfillService(
            HashtagBackfillRepository hashtagBackfillRepository,
            HashtagService hashtagService,
            TransactionTemplate transactionTemplate
    ) {
        this.hashtagBackfillRepository = hashtagBackfillRepository;
        this.hashtagService = hashtagService;
        this.transactionTemplate = transactionTemplate;
    }

    public HashtagBackfillResult backfill(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Hashtag backfill batch size must be between 1 and 1000.");
        }

        HashtagBackfillCursor cursor = null;
        long processedPosts = 0L;
        int batches = 0;

        while (true) {
            HashtagBackfillCursor currentCursor = cursor;
            BackfillBatch batch = transactionTemplate.execute(status ->
                    processBatch(currentCursor, batchSize)
            );
            if (batch == null || batch.processedPosts() == 0) {
                break;
            }

            processedPosts += batch.processedPosts();
            batches++;
            cursor = batch.nextCursor();
            if (batch.processedPosts() < batchSize) {
                break;
            }
        }

        return new HashtagBackfillResult(processedPosts, batches);
    }

    private BackfillBatch processBatch(HashtagBackfillCursor cursor, int batchSize) {
        List<Post> posts = hashtagBackfillRepository.findNextBatch(cursor, batchSize);
        for (Post post : posts) {
            hashtagService.attachToPost(post);
        }

        HashtagBackfillCursor nextCursor = posts.isEmpty()
                ? cursor
                : new HashtagBackfillCursor(
                        posts.getLast().getCreatedAt(),
                        posts.getLast().getId()
                );
        return new BackfillBatch(posts.size(), nextCursor);
    }

    private record BackfillBatch(int processedPosts, HashtagBackfillCursor nextCursor) {
    }
}
