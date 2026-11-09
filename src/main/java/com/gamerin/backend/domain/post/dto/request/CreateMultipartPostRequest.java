package com.gamerin.backend.domain.post.dto.request;

import java.util.UUID;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Size;

public class CreateMultipartPostRequest {

    private boolean asyncUpload;
    private UUID clientRequestId;

    public boolean isAsyncUpload() { return asyncUpload; }
    public void setAsyncUpload(boolean asyncUpload) { this.asyncUpload = asyncUpload; }
    public UUID getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(UUID clientRequestId) { this.clientRequestId = clientRequestId; }

    @Size(max = 1000, message = "Post content must be 1000 characters or fewer.")
    private String content;

    private List<MultipartFile> mediaFiles;

    private MultipartFile thumbnailFile;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<MultipartFile> getMediaFiles() {
        return mediaFiles;
    }

    public void setMediaFiles(List<MultipartFile> mediaFiles) {
        this.mediaFiles = mediaFiles;
    }

    public MultipartFile getThumbnailFile() {
        return thumbnailFile;
    }

    public void setThumbnailFile(MultipartFile thumbnailFile) {
        this.thumbnailFile = thumbnailFile;
    }
}
