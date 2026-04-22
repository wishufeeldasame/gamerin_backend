package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        RefreshToken refreshTokenEntity = RefreshToken.issue(
                user.getId(),
                sha256(refreshToken),
                OffsetDateTime.now().plusSeconds(refreshTokenExpirationSeconds),
                null,
                null
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