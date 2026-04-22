package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(jwtTokenProvider, refreshTokenRepository, 1209600L);
    }

    @Test
    void issueTokensCreatesAccessTokenAndPersistsHashedRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = User.createLocal("user@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);

        when(jwtTokenProvider.createAccessToken(eq(userId), eq("tester"), any()))
                .thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(1800L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TokenService.AuthResult result = tokenService.issueTokens(user);

        ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(refreshTokenCaptor.capture());

        RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();
        AuthTokenResponse response = result.authTokenResponse();

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.handle()).isEqualTo("tester");
        assertThat(response.nickname()).isEqualTo("Tester");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.accessTokenExpiresIn()).isEqualTo(1800L);

        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshTokenExpiresIn()).isEqualTo(1209600L);
        assertThat(savedRefreshToken.getUserId()).isEqualTo(userId);
        assertThat(savedRefreshToken.getTokenHash()).isEqualTo(tokenService.sha256(result.refreshToken()));
        assertThat(savedRefreshToken.getExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(savedRefreshToken.getUserAgent()).isNull();
        assertThat(savedRefreshToken.getIpAddress()).isNull();
    }

    @Test
    void sha256ReturnsDeterministicHash() {
        String first = tokenService.sha256("same-value");
        String second = tokenService.sha256("same-value");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }
}
