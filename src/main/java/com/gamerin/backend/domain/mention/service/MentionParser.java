package com.gamerin.backend.domain.mention.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.mention.model.ParsedMention;

@Component
public class MentionParser {

    public static final int MIN_HANDLE_LENGTH = 3;
    public static final int MAX_HANDLE_LENGTH = 20;

    public List<ParsedMention> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String normalizedContent = Normalizer.normalize(content, Normalizer.Form.NFC);
        List<ParsedMention> mentions = new ArrayList<>();

        int index = 0;
        while (index < normalizedContent.length()) {
            int codePoint = normalizedContent.codePointAt(index);
            if (codePoint != '@' || !isMentionBoundary(normalizedContent, index)) {
                index += Character.charCount(codePoint);
                continue;
            }

            int handleStart = index + 1;
            int handleEnd = handleStart;
            while (handleEnd < normalizedContent.length()) {
                int candidate = normalizedContent.codePointAt(handleEnd);
                if (!isCandidateCharacter(candidate)) {
                    break;
                }
                handleEnd += Character.charCount(candidate);
            }

            if (handleEnd > handleStart) {
                String rawHandle = normalizedContent.substring(handleStart, handleEnd);
                List<String> lookupCandidates = buildLookupCandidates(rawHandle);
                if (!lookupCandidates.isEmpty()) {
                    mentions.add(new ParsedMention(rawHandle, lookupCandidates));
                }
            }

            index = Math.max(handleEnd, index + 1);
        }

        return mentions;
    }

    private List<String> buildLookupCandidates(String rawHandle) {
        Set<String> candidates = new LinkedHashSet<>();
        String candidate = rawHandle;

        while (!candidate.isEmpty()) {
            if (isValidHandle(candidate)) {
                candidates.add(candidate);
            }
            if (!candidate.endsWith(".")) {
                break;
            }
            candidate = candidate.substring(0, candidate.length() - 1);
        }

        return List.copyOf(candidates);
    }

    private boolean isMentionBoundary(String content, int mentionIndex) {
        if (mentionIndex == 0) {
            return true;
        }

        int previous = content.codePointBefore(mentionIndex);
        if (Character.isWhitespace(previous) || Character.isSpaceChar(previous)) {
            return true;
        }
        if (isCandidateCharacter(previous)) {
            return false;
        }
        return previous != '@'
                && previous != '#'
                && previous != '/'
                && previous != '\\';
    }

    private boolean isCandidateCharacter(int codePoint) {
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '.') {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private boolean isValidHandle(String value) {
        int length = value.codePointCount(0, value.length());
        if (length < MIN_HANDLE_LENGTH || length > MAX_HANDLE_LENGTH) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '.') {
                continue;
            }
            return false;
        }
        return true;
    }
}
