package com.gamerin.backend.domain.message.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        MessageRecipientResponse recipient,
        List<MessageResponse> messages,
        long unreadCount,
        OffsetDateTime updatedAt
) {
}
