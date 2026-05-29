package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TextSecurityServiceTest {

    private final TextSecurityService textSecurityService = new TextSecurityService();

    @Test
    void assertTextSafeAllowsPlainText() {
        assertThatCode(() -> textSecurityService.assertTextSafe("오늘 배그 하이라이트 클립입니다"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertTextSafeRejectsUnsafeMarkup() {
        assertThatThrownBy(() -> textSecurityService.assertTextSafe("<script>alert(1)</script>"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void assertTextSafeRejectsControlCharacters() {
        assertThatThrownBy(() -> textSecurityService.assertTextSafe("hello\u0000world"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
