package com.gamerin.backend.global.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final String secret;
    private final long accessTokenExpirationSeconds;
    private SecretKey secretKey;

    public JwtTokenProvider(
            @Value("${app.auth.jwt.secret}") String secret,
            @Value("${app.auth.jwt.access-token-expiration-seconds:1800}") long accessTokenExpirationSeconds
    ) {
        this.secret = secret;
        this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
    }

    @PostConstruct
    void init() {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (RuntimeException e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createAccessToken(UUID userId, String handle, Collection<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpirationSeconds);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("handle", handle)
                .claim("roles", List.copyOf(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String createRegisterToken(String email, String provider, String providerId) {
        return Jwts.builder()
                .subject(email)
                .claim("provider", provider)
                .claim("providerId", providerId)
                .claim("type", "REGISTER")
                .expiration(new java.util.Date(System.currentTimeMillis() + 15 * 60 * 1000)) // 15분 유효
                .signWith(secretKey) 
                .compact();
    }

    public io.jsonwebtoken.Claims parseRegisterToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validate(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return UUID.fromString(claims.getSubject());
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }
}
