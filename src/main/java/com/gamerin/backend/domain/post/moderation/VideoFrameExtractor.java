package com.gamerin.backend.domain.post.moderation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.service.VideoMetadataService;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FailureType;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FfmpegProcessException;
import com.gamerin.backend.domain.post.service.VideoTemporaryStorageGuard;

@Service
public class VideoFrameExtractor {

    private static final String FRAME_EXTRACTION_FAILED_MESSAGE = "Video moderation frame could not be extracted.";
    private static final String FRAME_EXTRACTION_UNAVAILABLE_MESSAGE = "Video moderation is temporarily unavailable.";
    private static final Logger log = LoggerFactory.getLogger(VideoFrameExtractor.class);

    private final VideoMetadataService videoMetadataService;
    private final ImageModerationPreprocessor imageModerationPreprocessor;
    private final FfmpegProcessRunner ffmpegProcessRunner;
    private final VideoTemporaryStorageGuard temporaryStorageGuard;
    private final String ffmpegPath;
    private final long frameExtractionTimeoutMillis;

    public VideoFrameExtractor(
            VideoMetadataService videoMetadataService,
            ImageModerationPreprocessor imageModerationPreprocessor,
            FfmpegProcessRunner ffmpegProcessRunner,
            VideoTemporaryStorageGuard temporaryStorageGuard,
            @Value("${openai.moderation.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${openai.moderation.ffmpeg-timeout-ms:15000}") long frameExtractionTimeoutMillis
    ) {
        if (frameExtractionTimeoutMillis < 1) {
            throw new IllegalArgumentException("Video moderation FFmpeg timeout must be at least 1 ms.");
        }
        this.videoMetadataService = videoMetadataService;
        this.imageModerationPreprocessor = imageModerationPreprocessor;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
        this.temporaryStorageGuard = temporaryStorageGuard;
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
        this.frameExtractionTimeoutMillis = frameExtractionTimeoutMillis;
    }

    public List<String> extractFrameDataUrls(MultipartFile videoFile) {
        Path tempDirectory = null;
        try {
            double durationSeconds = videoMetadataService.readDurationSeconds(videoFile);
            temporaryStorageGuard.ensureCapacity(videoFile.getSize());
            tempDirectory = Files.createTempDirectory("gamerin-video-moderation-");
            Path inputVideo = tempDirectory.resolve("input" + extractExtension(videoFile.getOriginalFilename()));

            try (InputStream inputStream = videoFile.getInputStream()) {
                Files.copy(inputStream, inputVideo, StandardCopyOption.REPLACE_EXISTING);
            }

            List<Double> offsets = frameOffsets(durationSeconds);
            List<Path> framePaths = new ArrayList<>();
            for (int index = 0; index < offsets.size(); index++) {
                framePaths.add(tempDirectory.resolve("frame-" + index + ".jpg"));
            }

            for (int index = 0; index < offsets.size(); index++) {
                extractFrame(inputVideo, offsets.get(index), framePaths.get(index));
            }

            return framePaths.stream()
                    .map(imageModerationPreprocessor::toDataUrl)
                    .toList();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Video moderation frame extraction is not configured.", ex);
        } finally {
            deleteQuietly(tempDirectory);
        }
    }

    private List<Double> frameOffsets(double durationSeconds) {
        double safeDuration = Math.max(0.1, durationSeconds);
        return List.of(
                0.0,
                safeDuration / 2.0,
                Math.max(0.0, safeDuration - 0.1)
        );
    }

    private void extractFrame(Path inputVideo, double offsetSeconds, Path outputFrame) {
        List<String> command = List.of(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                String.format(Locale.ROOT, "%.3f", offsetSeconds),
                "-i",
                inputVideo.toString(),
                "-frames:v",
                "1",
                "-q:v",
                "3",
                outputFrame.toString()
        );

        try {
            ffmpegProcessRunner.run(command, frameExtractionTimeoutMillis);
            if (!Files.exists(outputFrame) || Files.size(outputFrame) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, FRAME_EXTRACTION_FAILED_MESSAGE);
            }
        } catch (FfmpegProcessException ex) {
            if (ex.failureType() == FailureType.COMMAND_FAILED) {
                log.warn("Video moderation FFmpeg failed with exit code {}: {}", ex.exitCode(), ex.output());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, FRAME_EXTRACTION_FAILED_MESSAGE, ex);
            }
            if (ex.failureType() == FailureType.TIMEOUT) {
                log.warn("Video moderation frame extraction timed out");
            } else {
                log.error("Video moderation FFmpeg is unavailable: {}", ex.failureType(), ex);
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, FRAME_EXTRACTION_UNAVAILABLE_MESSAGE, ex);
        } catch (IOException ex) {
            log.error("Failed to inspect extracted video moderation frame", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, FRAME_EXTRACTION_FAILED_MESSAGE, ex);
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return ".video";
        }

        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == originalFilename.length() - 1) {
            return ".video";
        }

        return originalFilename.substring(extensionIndex).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary moderation files are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary moderation files are best-effort cleanup.
        }
    }
}
