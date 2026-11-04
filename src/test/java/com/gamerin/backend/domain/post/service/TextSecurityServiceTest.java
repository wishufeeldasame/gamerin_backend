package com.gamerin.backend.domain.post.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "online = 1", "only=true", "I am online=yes", "once= again", "a < b and online = 1",
            "1 <3 only=1", "x<2 once=1", "<b>bold</b> and online = 1", "Tom & Jerry on=fire"
    })
    void assertTextSafeAllowsPlainTextResemblingEventHandlers(String text) {
        assertThatCode(() -> textSecurityService.assertTextSafe(text)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img src=x onerror=alert(1)>", "<IMG SRC=x ONERROR=alert(1)>", "<img src=x onerror = alert(1)>",
            "<img src=\"x\" onerror=\"alert(1)\">", "<img src='x' onerror='alert(1)'>",
            "<img\nsrc=x\nonerror=alert(1)>", "<img/src=x/onerror=alert(1)>", "<img src=x\tonerror\n=alert(1)>",
            "<img alt=\">\" onerror=alert(1)>", "<img alt=\"<\" onerror=alert(1)>",
            "<img src=x onerror=alert(1)", "<img src=x a=b=\" c='\" >' onerror=alert(1)>", "<img a=b\" c=\">\" src=x onerror=alert(1)>", "<img a = \">\" onerror=1>", "<img alt=\"x onerror=alert(1)", "<div onclick=alert(1)>x</div>",
            "ok <b>hi</b> <a href=# onmouseover=alert(1)>x</a>", "<body onload=alert(1)>", "<!-- --><p onfocus=x>"
    })
    void assertTextSafeRejectsEventHandlerInsideTag(String text) {
        assertThatThrownBy(() -> textSecurityService.assertTextSafe(text))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<ScRiPt>x", "< script >", "<svg/onload=1>", "javascript : alert(1)", "JAVASCRIPT:alert(1)"})
    void assertTextSafeStillRejectsOtherUnsafeMarkup(String text) {
        assertThatThrownBy(() -> textSecurityService.assertTextSafe(text))
                .isInstanceOf(ResponseStatusException.class);
    }
}
