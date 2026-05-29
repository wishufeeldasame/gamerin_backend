package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class LightweightSecurityScanServiceTest {

    private final LightweightSecurityScanService lightweightSecurityScanService = new LightweightSecurityScanService();

    @Test
    void assertFileCleanAllowsBenignFile() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "photo.jpg",
                "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}
        );

        assertThatCode(() -> lightweightSecurityScanService.assertFileClean(file))
                .doesNotThrowAnyException();
    }

    @Test
    void assertFileCleanRejectsExecutableHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "photo.jpg",
                "image/jpeg",
                new byte[] {'M', 'Z', 0x00, 0x00}
        );

        assertThatThrownBy(() -> lightweightSecurityScanService.assertFileClean(file))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assertFileCleanRejectsEicarSignature() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "prefix EICAR-STANDARD-ANTIVIRUS-TEST-FILE suffix".getBytes(StandardCharsets.US_ASCII)
        );

        assertThatThrownBy(() -> lightweightSecurityScanService.assertFileClean(file))
                .isInstanceOf(ResponseStatusException.class);
    }
}
