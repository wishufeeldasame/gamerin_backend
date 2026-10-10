package com.gamerin.backend.domain.hashtag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.hashtag.dto.response.HashtagBackfillResult;

@Component
@ConditionalOnProperty(name = "app.hashtag.backfill.enabled", havingValue = "true")
public class HashtagBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HashtagBackfillRunner.class);

    private final HashtagBackfillService hashtagBackfillService;
    private final int batchSize;

    public HashtagBackfillRunner(
            HashtagBackfillService hashtagBackfillService,
            @Value("${app.hashtag.backfill.batch-size:200}") int batchSize
    ) {
        this.hashtagBackfillService = hashtagBackfillService;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        HashtagBackfillResult result = hashtagBackfillService.backfill(batchSize);
        log.info(
                "Hashtag backfill completed: processedPosts={}, batches={}",
                result.processedPosts(),
                result.batches()
        );
    }
}
