package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaStorageServiceTest {

    @TempDir
    private Path uploadRoot;

    @Test
    void deletePublicUrlQuietlyDeletesFileUnderUploadRoot() throws Exception {
        Path mediaDirectory = uploadRoot.resolve("post-media");
        Files.createDirectories(mediaDirectory);
        Path mediaFile = mediaDirectory.resolve("media.jpg");
        Files.writeString(mediaFile, "image");

        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        mediaStorageService.deletePublicUrlQuietly("/uploads/post-media/media.jpg");

        assertThat(mediaFile).doesNotExist();
    }

    @Test
    void deletePublicUrlQuietlyIgnoresPathTraversal() throws Exception {
        Path outsideFile = Files.createTempFile(uploadRoot.getParent(), "outside-", ".txt");

        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        mediaStorageService.deletePublicUrlQuietly("/uploads/../outside.txt");

        assertThat(outsideFile).exists();
    }
}
