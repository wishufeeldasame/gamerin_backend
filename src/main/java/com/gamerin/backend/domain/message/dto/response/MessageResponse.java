package com.gamerin.backend.domain.message.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String senderId,
        String text,
        OffsetDateTime createdAt,
        boolean read,
        String deliveryStatus,
        List<MessageAttachmentResponse> attachments,
        SharedPostPreviewResponse sharedPost
) {
}
