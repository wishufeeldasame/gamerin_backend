package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {
        "spring.servlet.multipart.max-file-size=64MB",
        "spring.servlet.multipart.max-request-size=70MB",
        "app.media.video.max-file-size-bytes=12345",
        "app.media.video.optimization.max-concurrency=2",
        "app.media.video.optimization.acquire-timeout-ms=25"
})
class VideoUploadConfigurationIntegrationTest {

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private PostService postService;

    @Autowired
    private VideoOptimizationService videoOptimizationService;

    @Test
    void customUploadLimitsAndConcurrencySettingsAreInjected() {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(64L * 1024L * 1024L);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(70L * 1024L * 1024L);
        assertThat(ReflectionTestUtils.getField(postService, "maxVideoFileSizeBytes")).isEqualTo(12345L);
        assertThat(ReflectionTestUtils.getField(videoOptimizationService, "acquireTimeoutMillis")).isEqualTo(25L);

        Semaphore processingSlots = (Semaphore) ReflectionTestUtils.getField(
                videoOptimizationService,
                "processingSlots"
        );
        assertThat(processingSlots).isNotNull();
        assertThat(processingSlots.availablePermits()).isEqualTo(2);
    }
}
