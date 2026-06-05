package com.gamerin.backend.domain.message.dto.request;

import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Size;

public class SendMultipartMessageRequest {

    @Size(max = 2000)
    private String content;

    private UUID sharedPostId;

    private List<MultipartFile> attachments;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public UUID getSharedPostId() {
        return sharedPostId;
    }

    public void setSharedPostId(UUID sharedPostId) {
        this.sharedPostId = sharedPostId;
    }

    public List<MultipartFile> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<MultipartFile> attachments) {
        this.attachments = attachments;
    }
}
