package com.gamerin.backend.domain.payment.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PaymentCursor(
        OffsetDateTime createdAt,
        UUID paymentId
) {

    public static PaymentCursor parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split("\\|", -1);
        if (parts.length != 2) {
            throw invalidCursor();
        }

        try {
            return new PaymentCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw invalidCursor();
        }
    }

    public static String encode(OffsetDateTime createdAt, UUID paymentId) {
        return createdAt + "|" + paymentId;
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 결제 목록 커서입니다.");
    }
}
