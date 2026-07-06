package com.gamerin.backend.domain.post.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoTemporaryStorageGuard {

    private static final String INSUFFICIENT_STORAGE_MESSAGE = "비디오 처리를 위한 임시 저장 공간이 부족합니다.";
    private static final Logger log = LoggerFactory.getLogger(VideoTemporaryStorageGuard.class);

    private final long reserveBytes;
    private final UsableSpaceProvider usableSpaceProvider;

    @Autowired
    public VideoTemporaryStorageGuard(
            @Value("${app.media.video.temp-storage-reserve-bytes:536870912}") long reserveBytes
    ) {
        this(reserveBytes, path -> Files.getFileStore(path).getUsableSpace());
    }

    VideoTemporaryStorageGuard(long reserveBytes, UsableSpaceProvider usableSpaceProvider) {
        if (reserveBytes < 0) {
            throw new IllegalArgumentException("Video temporary storage reserve must not be negative.");
        }
        this.reserveBytes = reserveBytes;
        this.usableSpaceProvider = usableSpaceProvider;
    }

    public void ensureCapacity(long inputFileSizeBytes) {
        if (inputFileSizeBytes < 0) {
            throw new IllegalArgumentException("Video input file size must not be negative.");
        }

        Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        long requiredBytes = requiredBytes(inputFileSizeBytes);
        try {
            long usableBytes = usableSpaceProvider.getUsableSpace(temporaryDirectory);
            if (usableBytes < requiredBytes) {
                log.warn(
                        "Insufficient temporary storage for video processing: usableBytes={}, requiredBytes={}",
                        usableBytes,
                        requiredBytes
                );
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, INSUFFICIENT_STORAGE_MESSAGE);
            }
        } catch (IOException ex) {
            log.error("Failed to inspect temporary storage capacity", ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, INSUFFICIENT_STORAGE_MESSAGE, ex);
        }
    }

    long requiredBytes(long inputFileSizeBytes) {
        try {
            return Math.addExact(Math.multiplyExact(inputFileSizeBytes, 2L), reserveBytes);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    @FunctionalInterface
    interface UsableSpaceProvider {
        long getUsableSpace(Path path) throws IOException;
    }
}
