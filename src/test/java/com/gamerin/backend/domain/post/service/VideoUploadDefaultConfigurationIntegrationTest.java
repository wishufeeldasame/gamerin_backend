package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class VideoUploadDefaultConfigurationIntegrationTest {

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private PostService postService;

    @Autowired
    private VideoOptimizationService videoOptimizationService;

    @Test
    void defaultUploadPolicySupportsFiveHundredMebibyteVideos() {
        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(500L * 1024L * 1024L);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(530L * 1024L * 1024L);
        assertThat(ReflectionTestUtils.getField(postService, "maxVideoFileSizeBytes"))
                .isEqualTo(500L * 1024L * 1024L);
        assertThat(ReflectionTestUtils.getField(videoOptimizationService, "processingTimeoutMillis"))
                .isEqualTo(60_000L);
    }
}
