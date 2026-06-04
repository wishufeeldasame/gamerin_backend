package com.gamerin.backend.domain.message.dto.response;

import java.util.UUID;

public record MessageRealtimeEvent(
        String type,
        UUID conversationId,
        MessageResponse message,
        UUID messageId
) {
    public static MessageRealtimeEvent created(UUID conversationId, MessageResponse message) {
        return new MessageRealtimeEvent("message-created", conversationId, message, message.id());
    }

    public static MessageRealtimeEvent updated(UUID conversationId, MessageResponse message) {
        return new MessageRealtimeEvent("message-updated", conversationId, message, message.id());
    }

    public static MessageRealtimeEvent deleted(UUID conversationId, UUID messageId) {
        return new MessageRealtimeEvent("message-deleted", conversationId, null, messageId);
    }
}
