package com.gamerin.backend.domain.message.dto.response;

import java.util.UUID;

public record MessageAttachmentResponse(
        UUID id,
        String type,
        String name,
        String url
) {
}
