package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class VideoTemporaryStorageGuardTest {

    @Test
    void allowsProcessingWhenUsableSpaceMatchesRequiredSpace() {
        VideoTemporaryStorageGuard guard = new VideoTemporaryStorageGuard(512, path -> 2_512);

        assertThatCode(() -> guard.ensureCapacity(1_000)).doesNotThrowAnyException();
        assertThat(guard.requiredBytes(1_000)).isEqualTo(2_512);
    }

    @Test
    void rejectsProcessingWhenTemporaryStorageIsInsufficient() {
        VideoTemporaryStorageGuard guard = new VideoTemporaryStorageGuard(512, path -> 2_511);

        assertThatThrownBy(() -> guard.ensureCapacity(1_000))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getReason()).isEqualTo("비디오 처리를 위한 임시 저장 공간이 부족합니다.");
                });
    }

    @Test
    void returnsGenericUnavailableResponseWhenStorageCannotBeInspected() {
        VideoTemporaryStorageGuard guard = new VideoTemporaryStorageGuard(0, path -> {
            throw new IOException("secret mount path");
        });

        assertThatThrownBy(() -> guard.ensureCapacity(1_000))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception = (ResponseStatusException) error;
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getReason()).isEqualTo("비디오 처리를 위한 임시 저장 공간이 부족합니다.");
                    assertThat(exception.getReason()).doesNotContain("secret mount path");
                });
    }

    @Test
    void rejectsInvalidCapacityConfigurationAndInputSize() {
        assertThatThrownBy(() -> new VideoTemporaryStorageGuard(-1, path -> Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserve");

        VideoTemporaryStorageGuard guard = new VideoTemporaryStorageGuard(0, path -> Long.MAX_VALUE);
        assertThatThrownBy(() -> guard.ensureCapacity(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input file size");
    }

    @Test
    void treatsRequiredSpaceOverflowAsUnavailableCapacity() {
        VideoTemporaryStorageGuard guard = new VideoTemporaryStorageGuard(Long.MAX_VALUE, path -> Long.MAX_VALUE - 1);

        assertThat(guard.requiredBytes(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> guard.ensureCapacity(Long.MAX_VALUE))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
