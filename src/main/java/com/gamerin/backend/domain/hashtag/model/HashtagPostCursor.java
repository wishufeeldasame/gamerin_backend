package com.gamerin.backend.domain.hashtag.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record HashtagPostCursor(
        OffsetDateTime createdAt,
        UUID postId
) {

    public static HashtagPostCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split("\\|", -1);
        if (parts.length != 2) {
            throw invalidCursor();
        }

        try {
            return new HashtagPostCursor(
                    OffsetDateTime.parse(parts[0]),
                    UUID.fromString(parts[1])
            );
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw invalidCursor();
        }
    }

    public static String encode(OffsetDateTime createdAt, UUID postId) {
        return createdAt + "|" + postId;
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid hashtag cursor.");
    }
}
