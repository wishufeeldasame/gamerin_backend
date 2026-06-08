package com.gamerin.backend.global.security.jwt;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@Service
public class SseStreamTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;

    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
    private final long expirationSeconds;
    private final String cookieName;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public SseStreamTokenService(
            @Value("${app.auth.sse.stream-token-expiration-seconds:60}") long expirationSeconds,
            @Value("${app.auth.sse.stream-token-cookie-name:message_stream_token}") String cookieName,
            @Value("${app.auth.sse.stream-token-cookie-secure:false}") boolean cookieSecure,
            @Value("${app.auth.sse.stream-token-cookie-same-site:Lax}") String cookieSameSite
    ) {
        this.expirationSeconds = Math.max(10L, expirationSeconds);
        this.cookieName = cookieName;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = normalizeSameSite(cookieSameSite);
    }

    public IssuedToken issue(UUID userId) {
        Instant now = Instant.now();
        cleanupExpired(now);

        byte[] randomBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = TOKEN_ENCODER.encodeToString(randomBytes);
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        tokens.put(token, new TokenRecord(userId, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    public ResponseCookie createCookie(IssuedToken issuedToken) {
        return ResponseCookie.from(cookieName, issuedToken.token())
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/v1/messages/stream")
                .sameSite(cookieSameSite)
                .maxAge(expirationSeconds)
                .build();
    }

    public Optional<UUID> resolve(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }

        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return resolveToken(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<UUID> resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        cleanupExpired(now);
        TokenRecord record = tokens.get(token);
        if (record == null) {
            return Optional.empty();
        }
        if (record.expiresAt().isBefore(now)) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(record.userId());
    }

    private void cleanupExpired(Instant now) {
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String normalizeSameSite(String sameSite) {
        if (sameSite == null || sameSite.isBlank()) {
            return "Lax";
        }
        return sameSite.trim();
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private record TokenRecord(UUID userId, Instant expiresAt) {
    }
}
