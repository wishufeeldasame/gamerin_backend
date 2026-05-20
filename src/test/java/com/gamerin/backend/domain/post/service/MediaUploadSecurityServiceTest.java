package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class MediaUploadSecurityServiceTest {

    private final MediaUploadSecurityService mediaUploadSecurityService = new MediaUploadSecurityService();

    @Test
    void prepareImageCompressesPngToJpeg() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "screenshot.png",
                "image/png",
                imageBytes("png", 1200, 800)
        );

        MediaStorageService.PreparedMediaFile prepared = mediaUploadSecurityService.prepareImage(file);

        assertThat(prepared.extension()).isEqualTo(".jpg");
        assertThat(prepared.bytes()).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        assertThat(prepared.bytes().length).isLessThanOrEqualTo(5 * 1024 * 1024);
    }

    @Test
    void assertImageFileSafeRejectsContentTypeMismatch() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "photo.jpg",
                "image/png",
                imageBytes("jpg", 100, 100)
        );

        assertThatThrownBy(() -> mediaUploadSecurityService.assertImageFileSafe(file))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assertImageFileSafeRejectsOversizedImage() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(20L * 1024L * 1024L + 1L);
        when(file.getOriginalFilename()).thenReturn("photo.jpg");
        when(file.getContentType()).thenReturn("image/jpeg");

        assertThatThrownBy(() -> mediaUploadSecurityService.assertImageFileSafe(file))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assertVideoFileSafeAcceptsMp4FamilyHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}
        );

        mediaUploadSecurityService.assertVideoFileSafe(file);
    }

    @Test
    void assertVideoFileSafeRejectsInvalidHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "not-video".getBytes()
        );

        assertThatThrownBy(() -> mediaUploadSecurityService.assertVideoFileSafe(file))
                .isInstanceOf(ResponseStatusException.class);
    }

    private byte[] imageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, format, outputStream);
        return outputStream.toByteArray();
    }
}
