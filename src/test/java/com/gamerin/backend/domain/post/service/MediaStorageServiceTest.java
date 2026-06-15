package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaStorageServiceTest {

    @TempDir
    private Path uploadRoot;

    @Test
    void storeProfileImageStoresFileUnderProfileImageDirectory() throws Exception {
        UUID ownerId = UUID.randomUUID();
        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        MediaStorageService.StoredFile storedFile = mediaStorageService.storeProfileImage(
                ownerId,
                "profile",
                new MediaStorageService.PreparedMediaFile("image".getBytes(), ".jpg")
        );

        assertThat(storedFile.path()).exists();
        assertThat(storedFile.path())
                .startsWith(uploadRoot.resolve("profile-images").resolve(ownerId.toString()).resolve("profile"));
        assertThat(storedFile.publicUrl()).startsWith("/uploads/profile-images/" + ownerId + "/profile/");
        assertThat(storedFile.publicUrl()).endsWith(".jpg");
    }

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

    @Test
    void deleteOwnedProfileImageUrlQuietlyDoesNotDeletePostMedia() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Path mediaDirectory = uploadRoot.resolve("post-media");
        Files.createDirectories(mediaDirectory);
        Path mediaFile = mediaDirectory.resolve("media.jpg");
        Files.writeString(mediaFile, "image");

        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        mediaStorageService.deleteOwnedProfileImageUrlQuietly(ownerId, "profile", "/uploads/post-media/media.jpg");

        assertThat(mediaFile).exists();
    }

    @Test
    void deleteOwnedProfileImageUrlQuietlyDoesNotDeleteOtherUsersProfileImage() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Path imageDirectory = uploadRoot.resolve("profile-images").resolve(otherUserId.toString()).resolve("profile");
        Files.createDirectories(imageDirectory);
        Path imageFile = imageDirectory.resolve("image.jpg");
        Files.writeString(imageFile, "image");

        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        mediaStorageService.deleteOwnedProfileImageUrlQuietly(
                ownerId,
                "profile",
                "/uploads/profile-images/" + otherUserId + "/profile/image.jpg"
        );

        assertThat(imageFile).exists();
    }

    @Test
    void deleteOwnedProfileImageUrlQuietlyDoesNotDeleteDifferentTargetImage() throws Exception {
        UUID ownerId = UUID.randomUUID();
        Path imageDirectory = uploadRoot.resolve("profile-images").resolve(ownerId.toString()).resolve("cover");
        Files.createDirectories(imageDirectory);
        Path imageFile = imageDirectory.resolve("image.jpg");
        Files.writeString(imageFile, "image");

        MediaStorageService mediaStorageService = new MediaStorageService(uploadRoot.toString());

        mediaStorageService.deleteOwnedProfileImageUrlQuietly(
                ownerId,
                "profile",
                "/uploads/profile-images/" + ownerId + "/cover/image.jpg"
        );

        assertThat(imageFile).exists();
    }
}
