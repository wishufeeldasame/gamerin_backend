package com.gamerin.backend.domain.post.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoOptimizationService {

    private static final long FFMPEG_TIMEOUT_MILLIS = 60_000L;

    private final boolean enabled;
    private final String ffmpegPath;

    public VideoOptimizationService(
            @Value("${app.media.video.optimization.enabled:true}") boolean enabled,
            @Value("${app.media.video.optimization.ffmpeg-path:ffmpeg}") String ffmpegPath
    ) {
        this.enabled = enabled;
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public MediaStorageService.PreparedMediaPath prepareVideo(MultipartFile file) {
        Path inputVideo = null;
        Path outputVideo = null;
        try {
            inputVideo = Files.createTempFile("gamerin-video-input-", extractExtension(file.getOriginalFilename()));

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, inputVideo, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!enabled) {
                return new MediaStorageService.PreparedMediaPath(inputVideo, extractExtension(file.getOriginalFilename()));
            }

            outputVideo = Files.createTempFile("gamerin-video-output-", ".mp4");
            try {
                runFastRemux(inputVideo, outputVideo);
            } catch (ResponseStatusException remuxFailure) {
                runLowCostTranscode(inputVideo, outputVideo);
            }

            validateOutput(outputVideo);
            return new MediaStorageService.PreparedMediaPath(outputVideo, ".mp4");
        } catch (IOException ex) {
            deleteQuietly(outputVideo);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to prepare video file.", ex);
        } finally {
            if (enabled) {
                deleteQuietly(inputVideo);
            }
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
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ffmpeg is required for video optimization.", ex);
        }

        try {
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video optimization timed out.");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video could not be optimized. " + output.strip());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video optimization was interrupted.", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video optimization output could not be read.", ex);
        }
    }

    private void validateOutput(Path outputVideo) throws IOException {
        if (!Files.exists(outputVideo) || Files.size(outputVideo) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Optimized video file is empty.");
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
