package com.gamerin.backend.domain.message.dto.request;

import java.util.UUID;

public record CreateConversationRequest(
        String recipientHandle,
        UUID recipientId
) {
}
