package com.gamerin.backend.domain.message.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "direct_message_attachments")
public class DirectMessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id")
    private DirectMessage message;

    @Enumerated(EnumType.STRING)
    @Column(name = "attachment_type", nullable = false, length = 20)
    private MessageAttachmentType attachmentType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_url", nullable = false, columnDefinition = "TEXT")
    private String fileUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DirectMessageAttachment() {
    }

    public static DirectMessageAttachment create(
            DirectMessage message,
            MessageAttachmentType attachmentType,
            String fileName,
            String fileUrl,
            int sortOrder
    ) {
        DirectMessageAttachment attachment = new DirectMessageAttachment();
        attachment.message = message;
        attachment.attachmentType = attachmentType;
        attachment.fileName = fileName;
        attachment.fileUrl = fileUrl;
        attachment.sortOrder = sortOrder;
        return attachment;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public DirectMessage getMessage() {
        return message;
    }

    public MessageAttachmentType getAttachmentType() {
        return attachmentType;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
