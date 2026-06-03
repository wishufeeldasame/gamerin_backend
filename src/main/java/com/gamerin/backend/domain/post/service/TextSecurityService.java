package com.gamerin.backend.domain.post.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TextSecurityService {

    private static final Pattern UNSAFE_MARKUP_PATTERN = Pattern.compile(
            "(?i)<\\s*(script|iframe|object|embed|svg|link|meta)\\b|\\bon[a-z]+\\s*=|javascript\\s*:"
    );

    public void assertTextSafe(String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        if (containsDisallowedControlCharacter(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text contains invalid control characters.");
        }
        if (UNSAFE_MARKUP_PATTERN.matcher(content).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text contains unsafe markup.");
        }
    }

    private boolean containsDisallowedControlCharacter(String content) {
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t') {
                return true;
            }
        }
        return false;
    }
}
