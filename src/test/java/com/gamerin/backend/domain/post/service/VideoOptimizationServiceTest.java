package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class VideoOptimizationServiceTest {

    @Test
    void prepareVideoReturnsTemporaryOriginalWhenOptimizationDisabled() throws Exception {
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(false, "ffmpeg", 1, 0);
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

    @Test
    void prepareVideoRejectsWhenConcurrencyLimitIsReachedWithoutCreatingTemporaryFiles() throws Exception {
        Set<Path> tempFilesBefore = videoTempFiles();
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(true, "ffmpeg", 1, 0);
        Semaphore processingSlots = processingSlots(videoOptimizationService);
        assertThat(processingSlots.tryAcquire()).isTrue();

        try {
            assertThatThrownBy(() -> videoOptimizationService.prepareVideo(videoFile()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> {
                        ResponseStatusException exception = (ResponseStatusException) error;
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(exception.getReason())
                                .isEqualTo("현재 비디오 처리 요청이 많습니다. 잠시 후 다시 시도해주세요.");
                    });
            assertThat(processingSlots.availablePermits()).isZero();
            assertThat(videoTempFiles()).isEqualTo(tempFilesBefore);
        } finally {
            processingSlots.release();
        }
    }

    @Test
    void optimizationDisabledDoesNotConsumeConcurrencySlot() throws Exception {
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(false, "ffmpeg", 1, 0);
        Semaphore processingSlots = processingSlots(videoOptimizationService);
        assertThat(processingSlots.tryAcquire()).isTrue();

        MediaStorageService.PreparedMediaPath prepared = null;
        try {
            prepared = videoOptimizationService.prepareVideo(videoFile());
            assertThat(Files.readString(prepared.path())).isEqualTo("video-bytes");
            assertThat(processingSlots.availablePermits()).isZero();
        } finally {
            if (prepared != null) {
                Files.deleteIfExists(prepared.path());
            }
            processingSlots.release();
        }
    }

    @Test
    void ffmpegFailureDoesNotExposeProcessOutputAndReleasesResources() throws Exception {
        Set<Path> tempFilesBefore = videoTempFiles();
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(
                true,
                javaExecutable(),
                1,
                1000
        );

        assertThatThrownBy(() -> videoOptimizationService.prepareVideo(videoFile()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("비디오 파일을 처리할 수 없습니다.");
                    assertThat(exception.getReason()).doesNotContain("-hide_banner", "Unrecognized option");
                });

        assertThat(processingSlots(videoOptimizationService).availablePermits()).isEqualTo(1);
        assertThat(videoTempFiles()).isEqualTo(tempFilesBefore);
    }

    @Test
    void unavailableFfmpegReturnsGenericMessageAndReleasesResources() throws Exception {
        Set<Path> tempFilesBefore = videoTempFiles();
        String unavailableExecutable = Path.of(
                System.getProperty("java.io.tmpdir"),
                "missing-ffmpeg-" + UUID.randomUUID()
        ).toString();
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(
                true,
                unavailableExecutable,
                1,
                1000
        );

        assertThatThrownBy(() -> videoOptimizationService.prepareVideo(videoFile()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getReason()).isEqualTo("비디오 처리 서비스를 사용할 수 없습니다.");
                    assertThat(exception.getReason()).doesNotContain(unavailableExecutable, "missing-ffmpeg");
                });

        assertThat(processingSlots(videoOptimizationService).availablePermits()).isEqualTo(1);
        assertThat(videoTempFiles()).isEqualTo(tempFilesBefore);
    }

    @Test
    void interruptedConcurrencyWaitPreservesInterruptAndDoesNotReleaseUnownedPermit() {
        VideoOptimizationService videoOptimizationService = new VideoOptimizationService(true, "ffmpeg", 1, 5000);
        Semaphore processingSlots = processingSlots(videoOptimizationService);
        assertThat(processingSlots.tryAcquire()).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> videoOptimizationService.prepareVideo(videoFile()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> {
                        ResponseStatusException exception = (ResponseStatusException) error;
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(exception.getReason())
                                .isEqualTo("현재 비디오 처리 요청이 많습니다. 잠시 후 다시 시도해주세요.");
                    });

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(processingSlots.availablePermits()).isZero();
        } finally {
            Thread.interrupted();
            processingSlots.release();
        }
    }

    @Test
    void constructorRejectsInvalidConcurrencyConfiguration() {
        assertThatThrownBy(() -> new VideoOptimizationService(true, "ffmpeg", 0, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max concurrency");

        assertThatThrownBy(() -> new VideoOptimizationService(true, "ffmpeg", 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquire timeout");

        VideoOptimizationService configuredService = new VideoOptimizationService(true, "  ", 2, 25);
        assertThat(processingSlots(configuredService).availablePermits()).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(configuredService, "ffmpegPath")).isEqualTo("ffmpeg");
        assertThat(ReflectionTestUtils.getField(configuredService, "acquireTimeoutMillis")).isEqualTo(25L);
    }

    private MockMultipartFile videoFile() {
        return new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "video-bytes".getBytes()
        );
    }

    private Semaphore processingSlots(VideoOptimizationService service) {
        return (Semaphore) ReflectionTestUtils.getField(service, "processingSlots");
    }

    private String javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private Set<Path> videoTempFiles() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("gamerin-video-"))
                    .collect(Collectors.toSet());
        }
    }
}
