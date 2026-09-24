package com.gamerin.backend.domain.hashtag.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.hashtag.model.ParsedHashtag;

@Component
public class HashtagParser {

    public static final int MAX_HASHTAG_LENGTH = 50;

    public List<ParsedHashtag> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String normalizedContent = Normalizer.normalize(content, Normalizer.Form.NFC);
        Map<String, ParsedHashtag> uniqueHashtags = new LinkedHashMap<>();

        int index = 0;
        while (index < normalizedContent.length()) {
            int codePoint = normalizedContent.codePointAt(index);
            if (codePoint != '#' || !isHashtagBoundary(normalizedContent, index)) {
                index += Character.charCount(codePoint);
                continue;
            }

            int nameStart = index + 1;
            int nameEnd = nameStart;
            int codePointCount = 0;
            while (nameEnd < normalizedContent.length()) {
                int candidate = normalizedContent.codePointAt(nameEnd);
                if (!isHashtagCharacter(candidate, codePointCount > 0)) {
                    break;
                }
                codePointCount++;
                nameEnd += Character.charCount(candidate);
            }

            if (codePointCount >= 1 && codePointCount <= MAX_HASHTAG_LENGTH) {
                String displayName = normalizedContent.substring(nameStart, nameEnd);
                String normalizedName = normalizeName(displayName);
                uniqueHashtags.putIfAbsent(
                        normalizedName,
                        new ParsedHashtag(displayName, normalizedName)
                );
            }

            index = Math.max(nameEnd, index + 1);
        }

        return new ArrayList<>(uniqueHashtags.values());
    }

    public Optional<String> normalizeLookup(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String candidate = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (candidate.startsWith("#")) {
            candidate = candidate.substring(1);
        }

        int codePointCount = candidate.codePointCount(0, candidate.length());
        if (codePointCount < 1 || codePointCount > MAX_HASHTAG_LENGTH) {
            return Optional.empty();
        }

        int index = 0;
        int position = 0;
        while (index < candidate.length()) {
            int codePoint = candidate.codePointAt(index);
            if (!isHashtagCharacter(codePoint, position > 0)) {
                return Optional.empty();
            }
            position++;
            index += Character.charCount(codePoint);
        }

        return Optional.of(normalizeName(candidate));
    }

    private boolean isHashtagBoundary(String content, int hashtagIndex) {
        if (hashtagIndex == 0) {
            return true;
        }
        int previous = content.codePointBefore(hashtagIndex);
        return Character.isWhitespace(previous) || Character.isSpaceChar(previous);
    }

    private boolean isHashtagCharacter(int codePoint, boolean hasBaseCharacter) {
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
            return true;
        }
        if (!hasBaseCharacter) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private String normalizeName(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
