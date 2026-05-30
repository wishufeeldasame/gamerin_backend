package com.gamerin.backend.domain.post.service;

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

@Service
public class MediaStorageService {

    private static final String POST_MEDIA_DIRECTORY = "post-media";
    private static final String UPLOADS_PUBLIC_PATH = "/uploads/";

    private final Path uploadRoot;

    public MediaStorageService(@Value("${app.media.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public StoredFile storePostMedia(MultipartFile file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(POST_MEDIA_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String extension = extractExtension(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + extension;
        Path storedPath = targetDirectory.resolve(storedName).normalize();

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, storedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return new StoredFile(storedPath, publicPostMediaUrl(storedName));
    }

    public StoredFile storePostMedia(PreparedMediaFile file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(POST_MEDIA_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String storedName = UUID.randomUUID() + file.extension();
        Path storedPath = targetDirectory.resolve(storedName).normalize();

        Files.write(storedPath, file.bytes(), StandardOpenOption.CREATE_NEW);

        return new StoredFile(storedPath, publicPostMediaUrl(storedName));
    }

    public StoredFile storePostMedia(PreparedMediaPath file) throws IOException {
        Path targetDirectory = uploadRoot.resolve(POST_MEDIA_DIRECTORY);
        Files.createDirectories(targetDirectory);

        String storedName = UUID.randomUUID() + file.extension();
        Path storedPath = targetDirectory.resolve(storedName).normalize();

        Files.copy(file.path(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        return new StoredFile(storedPath, publicPostMediaUrl(storedName));
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

    public void deleteQuietly(PreparedMediaPath preparedMediaPath) {
        if (preparedMediaPath == null) {
            return;
        }

        try {
            Files.deleteIfExists(preparedMediaPath.path());
        } catch (IOException ignored) {
            // Best-effort cleanup for temporary prepared media files.
        }
    }

    public void deletePublicUrlQuietly(String publicUrl) {
        resolvePublicUrl(publicUrl).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best-effort cleanup for files belonging to a hard-deleted post.
            }
        });
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }

        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0 || extensionIndex == originalFilename.length() - 1) {
            return "";
        }

        return originalFilename.substring(extensionIndex).toLowerCase(Locale.ROOT);
    }

    private String publicPostMediaUrl(String storedName) {
        return UPLOADS_PUBLIC_PATH + POST_MEDIA_DIRECTORY + "/" + storedName;
    }

    private Optional<Path> resolvePublicUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return Optional.empty();
        }

        String path = extractPath(publicUrl.trim());
        if (!path.startsWith(UPLOADS_PUBLIC_PATH)) {
            return Optional.empty();
        }

        String relativePath = path.substring(UPLOADS_PUBLIC_PATH.length());
        if (relativePath.isBlank()) {
            return Optional.empty();
        }

        Path resolvedPath = uploadRoot.resolve(relativePath).normalize();
        if (!resolvedPath.startsWith(uploadRoot)) {
            return Optional.empty();
        }
        return Optional.of(resolvedPath);
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

    public record StoredFile(Path path, String publicUrl) {
    }

    public record PreparedMediaFile(byte[] bytes, String extension) {

        public PreparedMediaFile {
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("Prepared media file bytes are required.");
            }
            if (extension == null || extension.isBlank() || !extension.startsWith(".")) {
                throw new IllegalArgumentException("Prepared media file extension is required.");
            }
        }
    }

    public record PreparedMediaPath(Path path, String extension) {

        public PreparedMediaPath {
            if (path == null) {
                throw new IllegalArgumentException("Prepared media file path is required.");
            }
            if (extension == null || extension.isBlank() || !extension.startsWith(".")) {
                throw new IllegalArgumentException("Prepared media file extension is required.");
            }
        }
    }
}
