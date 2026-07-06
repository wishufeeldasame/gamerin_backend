package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FailureType;
import com.gamerin.backend.domain.post.service.FfmpegProcessRunner.FfmpegProcessException;

class FfmpegProcessRunnerTest {

    private final FfmpegProcessRunner runner = new FfmpegProcessRunner();

    @Test
    void capturesSuccessfulProcessOutput() {
        FfmpegProcessRunner.Result result = runner.run(javaCommand("success"), 5_000);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("ffmpeg-ok");
    }

    @Test
    void drainsLargeOutputWithoutDeadlockAndBoundsCapturedLog() {
        FfmpegProcessRunner.Result result = runner.run(javaCommand("flood"), 5_000);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).hasSize(4_003).endsWith("...");
    }

    @Test
    void terminatesProcessWhenTimeoutExpires() {
        FfmpegProcessException exception = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertThrows(
                        FfmpegProcessException.class,
                        () -> runner.run(javaCommand("sleep"), 100)
                )
        );

        assertThat(exception.failureType()).isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void drainsPipeSizedOutputBeforeTerminatingTimedOutProcess() {
        FfmpegProcessException exception = assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertThrows(
                        FfmpegProcessException.class,
                        () -> runner.run(javaCommand("flood-sleep"), 100)
                )
        );

        assertThat(exception.failureType()).isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void reportsUnavailableExecutableWithoutExposingCommandAsOutput() {
        String missingExecutable = Path.of(
                System.getProperty("java.io.tmpdir"),
                "missing-ffmpeg-" + UUID.randomUUID()
        ).toString();

        assertThatThrownBy(() -> runner.run(List.of(missingExecutable), 1_000))
                .isInstanceOf(FfmpegProcessException.class)
                .satisfies(error -> {
                    FfmpegProcessException exception = (FfmpegProcessException) error;
                    assertThat(exception.failureType()).isEqualTo(FailureType.UNAVAILABLE);
                    assertThat(exception.output()).isEmpty();
                });
    }

    @Test
    void interruptedCallerPreservesInterruptStatusAndStopsProcess() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.run(javaCommand("sleep"), 5_000))
                    .isInstanceOf(FfmpegProcessException.class)
                    .satisfies(error -> assertThat(((FfmpegProcessException) error).failureType())
                            .isEqualTo(FailureType.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void returnsBoundedInternalOutputForFailedCommand() {
        assertThatThrownBy(() -> runner.run(javaCommand("fail"), 5_000))
                .isInstanceOf(FfmpegProcessException.class)
                .satisfies(error -> {
                    FfmpegProcessException exception = (FfmpegProcessException) error;
                    assertThat(exception.failureType()).isEqualTo(FailureType.COMMAND_FAILED);
                    assertThat(exception.exitCode()).isEqualTo(7);
                    assertThat(exception.output()).contains("internal ffmpeg diagnostic secret");
                });
    }

    @Test
    void rejectsInvalidCommandsAndTimeouts() {
        assertThatThrownBy(() -> runner.run(List.of(), 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(javaCommand("success"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> javaCommand(String mode) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FfmpegProcessTestProgram.class.getName(),
                mode
        );
    }
}
