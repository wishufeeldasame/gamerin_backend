package com.gamerin.backend.domain.message.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @Size(max = 2000) String content,
        UUID sharedPostId,
        UUID clientMessageId
) {
    public SendMessageRequest(String content, UUID sharedPostId) {
        this(content, sharedPostId, null);
    }
}
