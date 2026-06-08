package com.gamerin.backend.domain.message.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.gamerin.backend.domain.post.service.MediaStorageService;

@Service
public class MessageAttachmentStorageService {

    private static final String MESSAGE_ATTACHMENT_DIRECTORY = "message-attachments";
    private static final String UPLOADS_PUBLIC_PATH = "/uploads/";

    private final Path uploadRoot;

    public MessageAttachmentStorageService(@Value("${app.media.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file, String extension) throws IOException {
        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String storedName = UUID.randomUUID() + sanitizeExtension(extension);
        Path storedPath = targetDirectory.resolve(storedName).normalize();
        if (!storedPath.startsWith(targetDirectory)) {
            throw new IOException("Invalid attachment file path.");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return new StoredFile(storedPath, storageKey(storedName));
    }

    public StoredFile store(MediaStorageService.PreparedMediaFile file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String storedName = UUID.randomUUID() + sanitizeExtension(file.extension());
        Path storedPath = targetDirectory.resolve(storedName).normalize();
        if (!storedPath.startsWith(targetDirectory)) {
            throw new IOException("Invalid attachment file path.");
        }

        Files.write(storedPath, file.bytes(), StandardOpenOption.CREATE_NEW);

        return new StoredFile(storedPath, storageKey(storedName));
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

    public Optional<Path> resolveStoredPath(String storedLocation) {
        if (storedLocation == null || storedLocation.isBlank()) {
            return Optional.empty();
        }

        String relativePath = extractRelativeStoragePath(storedLocation.trim());
        if (!relativePath.startsWith(MESSAGE_ATTACHMENT_DIRECTORY + "/")) {
            return Optional.empty();
        }

        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY).normalize();
        Path resolvedPath = uploadRoot.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(targetDirectory)) {
            return Optional.empty();
        }
        return Optional.of(resolvedPath);
    }

    private String extractRelativeStoragePath(String storedLocation) {
        String path = extractPath(storedLocation);
        if (path.startsWith(UPLOADS_PUBLIC_PATH)) {
            return path.substring(UPLOADS_PUBLIC_PATH.length());
        }
        if (path.startsWith("uploads/")) {
            return path.substring("uploads/".length());
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private String extractPath(String storedLocation) {
        try {
            URI uri = new URI(storedLocation);
            if (uri.getScheme() != null && uri.getPath() != null) {
                return uri.getPath();
            }
        } catch (URISyntaxException ignored) {
            // Treat malformed values as plain storage locations below.
        }
        return storedLocation;
    }

    private String sanitizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "";
        }

        String normalized = extension.toLowerCase(Locale.ROOT);
        return normalized.matches("\\.[a-z0-9]{1,10}") ? normalized : "";
    }

    private String storageKey(String storedName) {
        return MESSAGE_ATTACHMENT_DIRECTORY + "/" + storedName;
    }

    public record StoredFile(Path path, String storageKey) {
    }
}
