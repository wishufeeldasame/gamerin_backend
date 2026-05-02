package com.gamerin.backend.domain.post.dto.request;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class CreateMultipartPostRequest {

    @Size(max = 1000, message = "Post content must be 1000 characters or fewer.")
    private String content;

    @Size(max = 50, message = "Game name must be 50 characters or fewer.")
    private String gameName;

    private List<MultipartFile> mediaFiles;

    private MultipartFile thumbnailFile;

    @Min(value = 0, message = "Duration seconds cannot be negative.")
    private Integer durationSeconds;

    @Size(max = 2048, message = "External link URL must be 2048 characters or fewer.")
    private String externalLinkUrl;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getGameName() {
        return gameName;
    }

    public void setGameName(String gameName) {
        this.gameName = gameName;
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

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getExternalLinkUrl() {
        return externalLinkUrl;
    }

    public void setExternalLinkUrl(String externalLinkUrl) {
        this.externalLinkUrl = externalLinkUrl;
    }
}
