package com.gamerin.backend.domain.post.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

@Component
public class FfmpegProcessRunner {

    private static final int MAX_CAPTURED_OUTPUT_BYTES = 16_000;
    private static final int MAX_LOGGED_OUTPUT_LENGTH = 4_000;
    private static final long TERMINATION_GRACE_MILLIS = 2_000L;
    private static final long OUTPUT_READER_TIMEOUT_MILLIS = 5_000L;

    public Result run(List<String> command, long timeoutMillis) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("FFmpeg command must not be empty.");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("FFmpeg timeout must be at least 1 ms.");
        }

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw failure(FailureType.UNAVAILABLE, -1, "", ex);
        }

        FutureTask<String> outputTask = new FutureTask<>(() -> drainOutput(process.getInputStream()));
        Thread.ofVirtual().name("ffmpeg-output-reader").start(outputTask);

        try {
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                String output = awaitOutputAfterTermination(outputTask);
                throw failure(FailureType.TIMEOUT, -1, output, null);
            }

            String output = awaitOutput(outputTask);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw failure(FailureType.COMMAND_FAILED, exitCode, output, null);
            }
            return new Result(exitCode, output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            outputTask.cancel(true);
            throw failure(FailureType.INTERRUPTED, -1, "", ex);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String awaitOutput(FutureTask<String> outputTask) throws InterruptedException {
        try {
            return outputTask.get(OUTPUT_READER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw failure(FailureType.OUTPUT_READ_FAILED, -1, "", cause);
        } catch (TimeoutException ex) {
            outputTask.cancel(true);
            throw failure(FailureType.OUTPUT_READ_FAILED, -1, "", ex);
        }
    }

    private String awaitOutputAfterTermination(FutureTask<String> outputTask) throws InterruptedException {
        try {
            return awaitOutput(outputTask);
        } catch (FfmpegProcessException ex) {
            return "";
        }
    }

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private String drainOutput(InputStream inputStream) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(MAX_CAPTURED_OUTPUT_BYTES);
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            int remaining = MAX_CAPTURED_OUTPUT_BYTES - captured.size();
            if (remaining > 0) {
                captured.write(buffer, 0, Math.min(read, remaining));
            }
        }

        String normalized = captured.toString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.length() <= MAX_LOGGED_OUTPUT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOGGED_OUTPUT_LENGTH) + "...";
    }

    private FfmpegProcessException failure(
            FailureType failureType,
            int exitCode,
            String output,
            Throwable cause
    ) {
        return new FfmpegProcessException(failureType, exitCode, output, cause);
    }

    public enum FailureType {
        COMMAND_FAILED,
        TIMEOUT,
        UNAVAILABLE,
        INTERRUPTED,
        OUTPUT_READ_FAILED
    }

    public record Result(int exitCode, String output) {
    }

    public static final class FfmpegProcessException extends RuntimeException {

        private final FailureType failureType;
        private final int exitCode;
        private final String output;

        public FfmpegProcessException(
                FailureType failureType,
                int exitCode,
                String output,
                Throwable cause
        ) {
            super("FFmpeg process failed: " + failureType, cause);
            this.failureType = failureType;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public FailureType failureType() {
            return failureType;
        }

        public int exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }
    }
}
