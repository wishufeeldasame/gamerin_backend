package com.gamerin.backend.domain.search.model;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record AccountSearchCursor(
        String normalizedHandle,
        UUID userId
) {

    public static AccountSearchCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw invalidCursor();
        }

        try {
            return new AccountSearchCursor(parts[0], UUID.fromString(parts[1]));
        } catch (IllegalArgumentException error) {
            throw invalidCursor();
        }
    }

    public static String encode(String handle, UUID userId) {
        return handle.toLowerCase(java.util.Locale.ROOT) + "|" + userId;
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account search cursor.");
    }
}
