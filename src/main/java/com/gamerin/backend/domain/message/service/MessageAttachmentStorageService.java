package com.gamerin.backend.domain.message.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Service
public class MessageAttachmentStorageService {

    private static final String MESSAGE_ATTACHMENT_DIRECTORY = "message-attachments";

    private final Path uploadRoot;

    public MessageAttachmentStorageService(@Value("${app.media.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String extension = extractExtension(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + extension;
        Path storedPath = targetDirectory.resolve(storedName).normalize();
        if (!storedPath.startsWith(targetDirectory)) {
            throw new IOException("Invalid attachment file path.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String publicUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/uploads/")
                .path(MESSAGE_ATTACHMENT_DIRECTORY)
                .path("/")
                .path(storedName)
                .toUriString();

        return new StoredFile(storedPath, publicUrl);
    }

    public void deleteQuietly(StoredFile storedFile) {
        if (storedFile == null) {
            return;
        }

        try {
            Files.deleteIfExists(storedFile.path());
        } catch (IOException ignored) {
            // Best-effort cleanup for files written before a request fails.
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }

        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == originalFilename.length() - 1) {
            return "";
        }

        String extension = originalFilename.substring(extensionIndex).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    public record StoredFile(Path path, String publicUrl) {
    }
}
