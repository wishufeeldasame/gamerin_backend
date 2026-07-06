package com.gamerin.backend.domain.post.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FailureType;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FfmpegProcessException;

@Service
public class VideoOptimizationService {

    private static final String PROCESSING_BUSY_MESSAGE = "현재 비디오 처리 요청이 많습니다. 잠시 후 다시 시도해주세요.";
    private static final String PROCESSING_FAILED_MESSAGE = "비디오 파일을 처리할 수 없습니다.";
    private static final String PROCESSING_UNAVAILABLE_MESSAGE = "비디오 처리 서비스를 사용할 수 없습니다.";
    private static final Logger log = LoggerFactory.getLogger(VideoOptimizationService.class);

    private final boolean enabled;
    private final String ffmpegPath;
    private final Semaphore processingSlots;
    private final long acquireTimeoutMillis;
    private final long processingTimeoutMillis;
    private final FfmpegProcessRunner ffmpegProcessRunner;
    private final VideoTemporaryStorageGuard temporaryStorageGuard;

    public VideoOptimizationService(
            @Value("${app.media.video.optimization.enabled:true}") boolean enabled,
            @Value("${app.media.video.optimization.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${app.media.video.optimization.max-concurrency:1}") int maxConcurrency,
            @Value("${app.media.video.optimization.acquire-timeout-ms:5000}") long acquireTimeoutMillis,
            @Value("${app.media.video.optimization.timeout-ms:60000}") long processingTimeoutMillis,
            FfmpegProcessRunner ffmpegProcessRunner,
            VideoTemporaryStorageGuard temporaryStorageGuard
    ) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("Video optimization max concurrency must be at least 1.");
        }
        if (acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException("Video optimization acquire timeout must not be negative.");
        }
        if (processingTimeoutMillis < 1) {
            throw new IllegalArgumentException("Video optimization timeout must be at least 1 ms.");
        }

        this.enabled = enabled;
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
        this.processingSlots = new Semaphore(maxConcurrency, true);
        this.acquireTimeoutMillis = acquireTimeoutMillis;
        this.processingTimeoutMillis = processingTimeoutMillis;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
        this.temporaryStorageGuard = temporaryStorageGuard;
    }

    public MediaStorageService.PreparedMediaPath prepareVideo(MultipartFile file) {
        Path inputVideo = null;
        Path outputVideo = null;
        boolean processingSlotAcquired = false;
        boolean keepInputVideo = false;
        boolean keepOutputVideo = false;
        try {
            if (enabled) {
                acquireProcessingSlot();
                processingSlotAcquired = true;
            }

            temporaryStorageGuard.ensureCapacity(file.getSize());

            inputVideo = Files.createTempFile("gamerin-video-input-", extractExtension(file.getOriginalFilename()));

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, inputVideo, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!enabled) {
                keepInputVideo = true;
                return new MediaStorageService.PreparedMediaPath(inputVideo, extractExtension(file.getOriginalFilename()));
            }

            outputVideo = Files.createTempFile("gamerin-video-output-", ".mp4");
            try {
                runFastRemux(inputVideo, outputVideo);
            } catch (FfmpegProcessException remuxFailure) {
                if (remuxFailure.failureType() != FailureType.COMMAND_FAILED) {
                    throw toResponseStatusException(remuxFailure);
                }
                log.warn(
                        "FFmpeg remux failed with exit code {}; falling back to transcode: {}",
                        remuxFailure.exitCode(),
                        remuxFailure.output()
                );
                try {
                    runLowCostTranscode(inputVideo, outputVideo);
                } catch (FfmpegProcessException transcodeFailure) {
                    throw toResponseStatusException(transcodeFailure);
                }
            }

            validateOutput(outputVideo);
            keepOutputVideo = true;
            return new MediaStorageService.PreparedMediaPath(outputVideo, ".mp4");
        } catch (IOException ex) {
            log.error("Failed to prepare temporary video files", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, PROCESSING_FAILED_MESSAGE, ex);
        } finally {
            if (!keepInputVideo) {
                deleteQuietly(inputVideo);
            }
            if (!keepOutputVideo) {
                deleteQuietly(outputVideo);
            }
            if (processingSlotAcquired) {
                processingSlots.release();
            }
        }
    }

    private void acquireProcessingSlot() {
        try {
            if (!processingSlots.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, PROCESSING_BUSY_MESSAGE);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, PROCESSING_BUSY_MESSAGE, ex);
        }
    }

    private void runFastRemux(Path inputVideo, Path outputVideo) {
        runFfmpeg(List.of(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                inputVideo.toString(),
                "-map",
                "0:v:0",
                "-map",
                "0:a?",
                "-c",
                "copy",
                "-movflags",
                "+faststart",
                "-map_metadata",
                "-1",
                "-f",
                "mp4",
                outputVideo.toString()
        ));
    }

    private void runLowCostTranscode(Path inputVideo, Path outputVideo) {
        runFfmpeg(List.of(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                inputVideo.toString(),
                "-map",
                "0:v:0",
                "-map",
                "0:a?",
                "-vf",
                "scale=w=1920:h=1080:force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "24",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-movflags",
                "+faststart",
                "-map_metadata",
                "-1",
                "-f",
                "mp4",
                outputVideo.toString()
        ));
    }

    private void runFfmpeg(List<String> command) {
        ffmpegProcessRunner.run(command, processingTimeoutMillis);
    }

    private ResponseStatusException toResponseStatusException(FfmpegProcessException ex) {
        if (ex.failureType() == FailureType.COMMAND_FAILED) {
            log.warn("FFmpeg process failed with exit code {}: {}", ex.exitCode(), ex.output());
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, PROCESSING_FAILED_MESSAGE, ex);
        }

        if (ex.failureType() == FailureType.TIMEOUT) {
            log.warn("FFmpeg processing timed out");
        } else if (ex.failureType() == FailureType.INTERRUPTED) {
            log.warn("FFmpeg processing was interrupted", ex);
        } else {
            log.error("FFmpeg processing is unavailable: {}", ex.failureType(), ex);
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, PROCESSING_UNAVAILABLE_MESSAGE, ex);
    }

    private void validateOutput(Path outputVideo) throws IOException {
        if (!Files.exists(outputVideo) || Files.size(outputVideo) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, PROCESSING_FAILED_MESSAGE);
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

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary video preparation files are best-effort cleanup.
        }
    }
}
