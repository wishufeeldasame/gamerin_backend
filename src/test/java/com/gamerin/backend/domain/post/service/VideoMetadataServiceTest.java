package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class VideoMetadataServiceTest {

    private final VideoMetadataService videoMetadataService = new VideoMetadataService();

    @Test
    void readDurationSecondsReadsMp4MovieHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "video.mp4",
                "video/mp4",
                mp4WithDuration(120_000, 1_000)
        );

        double durationSeconds = videoMetadataService.readDurationSeconds(file);

        assertThat(durationSeconds).isEqualTo(120.0);
    }

    @Test
    void readDurationSecondsRejectsFileWithoutDurationMetadata() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "video.mp4",
                "video/mp4",
                "not a real mp4".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> videoMetadataService.readDurationSeconds(file))
                .isInstanceOf(ResponseStatusException.class);
    }

    private byte[] mp4WithDuration(int duration, int timescale) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(outputStream)) {
            writeBox(output, "ftyp", new byte[8]);
            writeBox(output, "moov", mvhdBox(duration, timescale));
        }
        return outputStream.toByteArray();
    }

    private byte[] mvhdBox(int duration, int timescale) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(outputStream)) {
            payload.writeByte(0);
            payload.write(new byte[3]);
            payload.writeInt(0);
            payload.writeInt(0);
            payload.writeInt(timescale);
            payload.writeInt(duration);
        }

        ByteArrayOutputStream boxStream = new ByteArrayOutputStream();
        try (DataOutputStream box = new DataOutputStream(boxStream)) {
            writeBox(box, "mvhd", outputStream.toByteArray());
        }
        return boxStream.toByteArray();
    }

    private void writeBox(DataOutputStream output, String type, byte[] payload) throws IOException {
        output.writeInt(8 + payload.length);
        output.write(type.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
    }
}
