package com.gamerin.backend.domain.message.service;

import java.io.IOException;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.gamerin.backend.domain.post.service.MediaStorageService;

@Service
public class MessageAttachmentStorageService {

    private static final String MESSAGE_ATTACHMENT_DIRECTORY = "message-attachments";

    private final Path uploadRoot;

    public MessageAttachmentStorageService(@Value("${app.media.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public StoredFile store(MediaStorageService.PreparedMediaFile file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String extension = normalizeServerExtension(file.extension());
        String storedName = UUID.randomUUID() + extension;
        Path storedPath = targetDirectory.resolve(storedName).normalize();
        if (!storedPath.startsWith(targetDirectory)) {
            throw new IOException("Invalid attachment file path.");
        }

        Files.write(storedPath, file.bytes(), StandardOpenOption.CREATE_NEW);

        return new StoredFile(storedPath, publicAttachmentUrl(storedName));
    }

    public StoredFile store(MediaStorageService.PreparedMediaPath file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String extension = normalizeServerExtension(file.extension());
        String storedName = UUID.randomUUID() + extension;
        Path storedPath = targetDirectory.resolve(storedName).normalize();
        if (!storedPath.startsWith(targetDirectory)) {
            throw new IOException("Invalid attachment file path.");
        }

        Files.copy(file.path(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        return new StoredFile(storedPath, publicAttachmentUrl(storedName));
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

    public void deleteQuietly(MediaStorageService.PreparedMediaPath preparedMediaPath) {
        if (preparedMediaPath == null) {
            return;
        }

        try {
            Files.deleteIfExists(preparedMediaPath.path());
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary prepared attachment files.
        }
    }

    public void deletePublicUrlQuietly(String publicUrl) {
        resolvePublicUrl(publicUrl).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best-effort cleanup for deleted message attachments.
            }
        });
    }

    public Optional<Path> resolvePublicUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return Optional.empty();
        }

        String path = extractPath(publicUrl.trim());
        String requiredPrefix = "/uploads/" + MESSAGE_ATTACHMENT_DIRECTORY + "/";
        if (!path.startsWith(requiredPrefix)) {
            return Optional.empty();
        }

        String relativePath = path.substring("/uploads/".length());
        Path resolvedPath = uploadRoot.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(uploadRoot.resolve(MESSAGE_ATTACHMENT_DIRECTORY).normalize())) {
            return Optional.empty();
        }
        return Optional.of(resolvedPath);
    }

    private String normalizeServerExtension(String extension) throws IOException {
        if (extension == null || extension.isBlank()) {
            throw new IOException("Attachment file extension is required.");
        }

        String normalized = extension.toLowerCase(Locale.ROOT);
        if (!normalized.matches("\\.[a-z0-9]{1,10}")) {
            throw new IOException("Invalid attachment file extension.");
        }
        return normalized;
    }

    private String extractPath(String publicUrl) {
        try {
            URI uri = new URI(publicUrl);
            if (uri.getScheme() != null && uri.getPath() != null) {
                return uri.getPath();
            }
        } catch (URISyntaxException ignored) {
            // Treat malformed values as plain paths below.
        }
        return publicUrl;
    }

    private String publicAttachmentUrl(String storedName) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/uploads/")
                .path(MESSAGE_ATTACHMENT_DIRECTORY)
                .path("/")
                .path(storedName)
                .toUriString();
    }

    public record StoredFile(Path path, String publicUrl) {
    }
}
