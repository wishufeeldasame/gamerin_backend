package com.gamerin.backend.domain.message.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SharePostMessageRequest(
        @NotNull UUID postId,
        List<String> recipientHandles,
        List<UUID> recipientIds,
        @Size(max = 2000) String content
) {
}
