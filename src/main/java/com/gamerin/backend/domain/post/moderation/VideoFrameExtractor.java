package com.gamerin.backend.domain.post.moderation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.service.VideoMetadataService;

@Service
public class VideoFrameExtractor {

    private static final long FRAME_EXTRACTION_TIMEOUT_MILLIS = 15_000L;

    private final VideoMetadataService videoMetadataService;
    private final ImageModerationPreprocessor imageModerationPreprocessor;
    private final String ffmpegPath;

    public VideoFrameExtractor(
            VideoMetadataService videoMetadataService,
            ImageModerationPreprocessor imageModerationPreprocessor,
            @Value("${openai.moderation.ffmpeg-path:ffmpeg}") String ffmpegPath
    ) {
        this.videoMetadataService = videoMetadataService;
        this.imageModerationPreprocessor = imageModerationPreprocessor;
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public List<String> extractFrameDataUrls(MultipartFile videoFile) {
        Path tempDirectory = null;
        try {
            double durationSeconds = videoMetadataService.readDurationSeconds(videoFile);
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

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ffmpeg is required for video moderation.", ex);
        }

        try {
            boolean finished = process.waitFor(FRAME_EXTRACTION_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video frame extraction timed out.");
            }
            if (process.exitValue() != 0 || !Files.exists(outputFrame) || Files.size(outputFrame) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video frame could not be extracted. " + output.strip());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video frame extraction was interrupted.", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video frame could not be read after extraction.", ex);
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
