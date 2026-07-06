package com.gamerin.backend.domain.message.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.ResponseCookie;

import com.gamerin.backend.domain.message.dto.response.MessageStreamTokenResponse;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.jwt.SseStreamTokenService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private MessageService messageService;

    @Mock
    private SseStreamTokenService sseStreamTokenService;

    private MessageController messageController;

    @BeforeEach
    void setUp() {
        messageController = new MessageController(messageService, sseStreamTokenService);
    }

    @Test
    void issueStreamTokenSetsHttpOnlyCookieAndReturnsExpiration() {
        User user = User.createLocal("viewer@example.com", "viewer", "Viewer", "encoded-password");
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        Instant expiresAt = Instant.parse("2026-06-24T12:00:00Z");
        SseStreamTokenService.IssuedToken issuedToken =
                new SseStreamTokenService.IssuedToken("stream-token", expiresAt);
        ResponseCookie cookie = ResponseCookie.from("message_stream_token", "stream-token")
                .httpOnly(true)
                .path("/api/v1/messages/stream")
                .maxAge(60)
                .build();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        when(messageService.issueStreamToken(principal)).thenReturn(issuedToken);
        when(sseStreamTokenService.createCookie(issuedToken)).thenReturn(cookie);

        ApiResponse<MessageStreamTokenResponse> response =
                messageController.issueStreamToken(principal, servletResponse);

        assertThat(response.success()).isTrue();
        assertThat(response.data().expiresAt()).isEqualTo(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        assertThat(servletResponse.getHeader("Set-Cookie")).contains("message_stream_token=stream-token");
        assertThat(servletResponse.getHeader("Set-Cookie")).contains("HttpOnly");
        assertThat(servletResponse.getHeader("Set-Cookie")).contains("Path=/api/v1/messages/stream");
    }
}
