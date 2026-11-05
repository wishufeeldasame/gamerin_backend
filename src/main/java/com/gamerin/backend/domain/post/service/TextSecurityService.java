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

    // 태그 시작(<영문자)부터 첫 '>'까지를 태그로 본다. 단, 그 안에 따옴표가 있으면 값 안의 '>'일 수 있고
    // HTML 파서의 따옴표 상태를 흉내 내면 우회가 생기므로(Codex 리뷰), 문자열 끝까지를 태그로 보아 보수적으로 차단한다.
    // 닫히지 않은 태그도 문자열 끝까지 검사한다.
    private boolean containsEventHandlerInTag(String content) {
        var tagStart = TAG_START_PATTERN.matcher(content);
        int from = 0;
        while (tagStart.find(from)) {
            int begin = tagStart.start();
            int end = content.indexOf('>', begin);
            int quote = indexOfQuote(content, begin);
            if (end == -1 || (quote != -1 && quote < end)) {
                end = content.length();
            }
            if (EVENT_HANDLER_PATTERN.matcher(content).region(begin, end).find()) {
                return true;
            }
            from = end;
        }
        return false;
    }

    private int indexOfQuote(String content, int from) {
        for (int i = from; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' || c == '\'') {
                return i;
            }
        }
        return -1;
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
