package com.gamerin.backend.domain.bookmark.service;

import java.text.Normalizer;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record BookmarkCollectionName(String displayName, String normalizedName) {

    private static final int MAX_CODE_POINTS = 40;

    static BookmarkCollectionName from(String rawName) {
        if (rawName == null) {
            throw invalidName();
        }

        String displayName = stripWhitespace(rawName);
        int codePointCount = displayName.codePointCount(0, displayName.length());
        if (displayName.isBlank() || codePointCount > MAX_CODE_POINTS) {
            throw invalidName();
        }

        String normalizedName = Normalizer.normalize(displayName, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        if (normalizedName.isBlank()) {
            throw invalidName();
        }

        return new BookmarkCollectionName(displayName, normalizedName);
    }

    private static String stripWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ResponseStatusException invalidName() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Collection name must be between 1 and 40 characters after trimming."
        );
    }
}
