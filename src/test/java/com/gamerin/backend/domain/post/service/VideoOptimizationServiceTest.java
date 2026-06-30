package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class VideoOptimizationServiceTest {

    @Test
    void prepareVideoReturnsTemporaryOriginalWhenOptimizationDisabled() throws Exception {
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(false, "ffmpeg");
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "video-bytes".getBytes()
        );

        MediaStorageService.PreparedMediaPath prepared = videoOptimizationService.prepareVideo(file);

        assertThat(prepared.extension()).isEqualTo(".mp4");
        assertThat(Files.readString(prepared.path())).isEqualTo("video-bytes");

        Files.deleteIfExists(prepared.path());
    }
}
