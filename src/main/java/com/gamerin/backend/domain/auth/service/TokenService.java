package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
@Transactional
public class TokenService {

    static final int MAX_USER_AGENT_LENGTH = 512;
    static final int MAX_IP_ADDRESS_LENGTH = 45;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTokenExpirationSeconds;

    public TokenService(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.auth.jwt.refresh-token-expiration-seconds:1209600}") long refreshTokenExpirationSeconds
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    // Access Token + Refresh Token 동시 발급
    public AuthResult issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                user.getRole() == null ? List.of("USER") : List.of(user.getRole().name())
        );

        String refreshToken = generateOpaqueToken();
        HttpServletRequest request = currentRequest();
        RefreshToken refreshTokenEntity = RefreshToken.issue(
                user.getId(),
                sha256(refreshToken),
                OffsetDateTime.now().plusSeconds(refreshTokenExpirationSeconds),
                request == null ? null : truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH),
                request == null ? null : truncate(request.getRemoteAddr(), MAX_IP_ADDRESS_LENGTH)
        );
        refreshTokenRepository.save(refreshTokenEntity);

        AuthTokenResponse response = new AuthTokenResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                accessToken,
                jwtTokenProvider.getAccessTokenExpirationSeconds()
        );

        return new AuthResult(response, refreshToken, refreshTokenExpirationSeconds);
    }

    // 발급 요청의 접속 주소와 User-Agent를 기록한다. X-Forwarded-For는 직접 읽지 않고,
    // 운영(prod)에서는 forward-headers-strategy: framework가 신뢰 프록시 기준으로 반영한 getRemoteAddr()를 쓴다(RateLimitFilter와 같은 기준).
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes ? servletAttributes.getRequest() : null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    // 랜덤 토큰 생성
    public String generateOpaqueToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // SHA-256 해시 암호화
    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    // 서비스들끼리 주고받을 결과 포맷 DTO
    public record AuthResult(
            AuthTokenResponse authTokenResponse,
            String refreshToken,
            long refreshTokenExpiresIn
    ) {
    }
}