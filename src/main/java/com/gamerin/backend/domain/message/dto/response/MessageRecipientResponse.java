package com.gamerin.backend.domain.message.dto.response;

import java.util.UUID;

public record MessageRecipientResponse(
        UUID id,
        String name,
        String handle,
        String role,
        boolean online,
        String profileImageUrl
) {
}
