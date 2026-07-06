package com.gamerin.backend.domain.post.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.service.FfmpegProcessRunner;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FailureType;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FfmpegProcessException;
import com.gamerin.backend.domain.post.service.VideoMetadataService;
import com.gamerin.backend.domain.post.service.VideoTemporaryStorageGuard;

class VideoFrameExtractorTest {

    @Test
    void successfulExtractionCreatesThreeModerationFramesAndCleansTemporaryDirectory() throws Exception {
        Set<Path> tempDirectoriesBefore = moderationTempDirectories();
        FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
        VideoMetadataService metadataService = mock(VideoMetadataService.class);
        VideoTemporaryStorageGuard storageGuard = mock(VideoTemporaryStorageGuard.class);
        ImageModerationPreprocessor preprocessor = mock(ImageModerationPreprocessor.class);
        MockMultipartFile video = videoFile();
        when(metadataService.readDurationSeconds(video)).thenReturn(10.0);
        when(runner.run(anyList(), eq(100L))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            writeJpeg(Path.of(command.getLast()));
            return new FfmpegProcessRunner.Result(0, "");
        });
        when(preprocessor.toDataUrl(any(Path.class)))
                .thenReturn("frame-1", "frame-2", "frame-3");
        VideoFrameExtractor extractor = new VideoFrameExtractor(
                metadataService,
                preprocessor,
                runner,
                storageGuard,
                "ffmpeg",
                100
        );

        List<String> frames = extractor.extractFrameDataUrls(video);

        assertThat(frames).containsExactly("frame-1", "frame-2", "frame-3");
        verify(runner, times(3)).run(anyList(), eq(100L));
        verify(preprocessor, times(3)).toDataUrl(any(Path.class));
        verify(storageGuard).ensureCapacity(video.getSize());
        assertThat(moderationTempDirectories()).isEqualTo(tempDirectoriesBefore);
    }

    @Test
    void commandFailureDoesNotExposeFfmpegOutput() {
        FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
        VideoMetadataService metadataService = mock(VideoMetadataService.class);
        VideoTemporaryStorageGuard storageGuard = mock(VideoTemporaryStorageGuard.class);
        MockMultipartFile video = videoFile();
        when(metadataService.readDurationSeconds(video)).thenReturn(10.0);
        when(runner.run(anyList(), eq(100L))).thenThrow(new FfmpegProcessException(
                FailureType.COMMAND_FAILED,
                1,
                "secret internal ffmpeg output",
                null
        ));
        VideoFrameExtractor extractor = extractor(metadataService, runner, storageGuard, 100);

        assertThatThrownBy(() -> extractor.extractFrameDataUrls(video))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("Video moderation frame could not be extracted.");
                    assertThat(exception.getReason()).doesNotContain("secret internal ffmpeg output");
                });

        verify(storageGuard).ensureCapacity(video.getSize());
    }

    @Test
    void timeoutReturnsGenericServiceUnavailableResponse() {
        FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
        VideoMetadataService metadataService = mock(VideoMetadataService.class);
        VideoTemporaryStorageGuard storageGuard = mock(VideoTemporaryStorageGuard.class);
        MockMultipartFile video = videoFile();
        when(metadataService.readDurationSeconds(video)).thenReturn(10.0);
        when(runner.run(anyList(), eq(100L))).thenThrow(new FfmpegProcessException(
                FailureType.TIMEOUT,
                -1,
                "secret timeout output",
                null
        ));
        VideoFrameExtractor extractor = extractor(metadataService, runner, storageGuard, 100);

        assertThatThrownBy(() -> extractor.extractFrameDataUrls(video))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getReason()).isEqualTo("Video moderation is temporarily unavailable.");
                    assertThat(exception.getReason()).doesNotContain("secret timeout output");
                });
    }

    @Test
    void constructorRejectsInvalidTimeout() {
        assertThatThrownBy(() -> extractor(
                mock(VideoMetadataService.class),
                mock(FfmpegProcessRunner.class),
                mock(VideoTemporaryStorageGuard.class),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    private VideoFrameExtractor extractor(
            VideoMetadataService metadataService,
            FfmpegProcessRunner runner,
            VideoTemporaryStorageGuard storageGuard,
            long timeoutMillis
    ) {
        return new VideoFrameExtractor(
                metadataService,
                mock(ImageModerationPreprocessor.class),
                runner,
                storageGuard,
                "ffmpeg",
                timeoutMillis
        );
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "video-bytes".getBytes()
        );
    }

    private void writeJpeg(Path path) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "jpg", path.toFile());
    }

    private Set<Path> moderationTempDirectories() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith("gamerin-video-moderation-"))
                    .collect(Collectors.toSet());
        }
    }
}
