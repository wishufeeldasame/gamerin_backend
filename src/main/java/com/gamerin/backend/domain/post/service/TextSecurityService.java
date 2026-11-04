package com.gamerin.backend.domain.post.service;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TextSecurityService {

    private static final Pattern UNSAFE_MARKUP_PATTERN = Pattern.compile(
            "(?i)<\\s*(script|iframe|object|embed|svg|link|meta)\\b|javascript\\s*:"
    );
    // 이벤트 핸들러 속성은 태그 문맥(<tag ... on...=)에서만 차단한다. 평문 "online = 1" 오탐 방지.
    private static final Pattern TAG_START_PATTERN = Pattern.compile("<[a-zA-Z!/?]");
    private static final Pattern EVENT_HANDLER_PATTERN = Pattern.compile("(?i)\\bon[a-z]+\\s*=");

    public void assertTextSafe(String content) {
        if (content == null || content.isBlank()) {
            return;
        }

        if (containsDisallowedControlCharacter(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text contains invalid control characters.");
        }
        if (UNSAFE_MARKUP_PATTERN.matcher(content).find() || containsEventHandlerInTag(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text contains unsafe markup.");
        }
    }

    // 따옴표 안의 '>'는 태그 끝이 아니므로(<img alt=">" onerror=...>) 따옴표를 추적한다.
    // 닫히지 않은 태그/따옴표는 문자열 끝까지를 태그로 보아 보수적으로 차단한다.
    private boolean containsEventHandlerInTag(String content) {
        var tagStart = TAG_START_PATTERN.matcher(content);
        int from = 0;
        while (tagStart.find(from)) {
            int begin = tagStart.start();
            int end = begin + 1;
            char quote = 0;
            char prev = 0; // 따옴표 밖 직전 비공백 문자: HTML은 '=' 바로 뒤의 따옴표만 값 시작으로 본다.
            while (end < content.length()) {
                char c = content.charAt(end);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if ((c == '"' || c == '\'') && prev == '=') {
                    quote = c;
                } else if (c == '>') {
                    break;
                }
                if (quote == 0 && !Character.isWhitespace(c)) {
                    prev = c;
                }
                end++;
            }
            if (EVENT_HANDLER_PATTERN.matcher(content).region(begin, end).find()) {
                return true;
            }
            from = end;
        }
        return false;
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
