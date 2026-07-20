package com.gamerin.backend.domain.repost.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record TimelineCursor(
        OffsetDateTime snapshotAt,
        OffsetDateTime activityAt,
        UUID postId
) {

    public static TimelineCursor parseOrStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return new TimelineCursor(OffsetDateTime.now(ZoneOffset.UTC), null, null);
        }

        try {
            String[] values = raw.split("\\|", -1);
            if (values.length == 2) {
                return new TimelineCursor(
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.parse(values[0]),
                        UUID.fromString(values[1])
                );
            }
            if (values.length == 3) {
                return new TimelineCursor(
                        OffsetDateTime.parse(values[0]),
                        OffsetDateTime.parse(values[1]),
                        UUID.fromString(values[2])
                );
            }
            throw invalidCursor();
        } catch (DateTimeParseException | IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    public boolean hasPosition() {
        return activityAt != null && postId != null;
    }

    public String next(OffsetDateTime nextActivityAt, UUID nextPostId) {
        return snapshotAt + "|" + nextActivityAt + "|" + nextPostId;
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timeline cursor.");
    }
}
