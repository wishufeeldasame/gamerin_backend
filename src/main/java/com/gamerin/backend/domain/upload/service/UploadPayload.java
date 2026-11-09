package com.gamerin.backend.domain.upload.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

/** 파일 내용은 DB에 넣지 않고 비공개 작업 디렉토리의 고정 이름으로 보관한다. */
public record UploadPayload(String content, UUID conversationId, UUID sharedPostId,
        List<Part> files, Part thumbnail) {
    public record Part(String storedName, String filename, String contentType, long size, String sha256) {
        public MultipartFile open(Path directory) { return new StoredPart(directory.resolve(storedName), this); }
    }

    private record StoredPart(Path path, Part part) implements MultipartFile {
        @Override public String getName() { return part.storedName(); }
        @Override public String getOriginalFilename() { return part.filename(); }
        @Override public String getContentType() { return part.contentType(); }
        @Override public boolean isEmpty() { return part.size() == 0; }
        @Override public long getSize() { return part.size(); }
        @Override public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        @Override public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        @Override public void transferTo(File destination) throws IOException {
            Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
