package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.request.FindIdRequest;
import com.gamerin.backend.domain.auth.dto.request.FindPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.LoginRequest;
import com.gamerin.backend.domain.auth.dto.request.ResetPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.SignUpRequest;
import com.gamerin.backend.domain.auth.dto.request.SocialSignUpRequest;
import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.dto.response.FindIdResponse;
import com.gamerin.backend.domain.auth.entity.PasswordResetToken;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.entity.SocialAccount;
import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.repository.PasswordResetTokenRepository;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private SocialSignupSessionRepository socialSignupSessionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @Mock
    private PasswordResetMailService passwordResetMailService;

    private LocalAuthService localAuthService;

    @BeforeEach
    void setUp() {
        localAuthService = new LocalAuthService(
                userRepository,
                refreshTokenRepository,
                passwordResetTokenRepository,
                socialAccountRepository,
                socialSignupSessionRepository,
                passwordEncoder,
                tokenService,
                passwordResetMailService,
                30L
        );
    }

    @Test
    void checkHandleAvailabilityNormalizesHandleBeforeLookup() {
        when(userRepository.existsByHandle("test.user")).thenReturn(false);

        var response = localAuthService.checkHandleAvailability("  Test.User  ");

        assertThat(response.handle()).isEqualTo("test.user");
        assertThat(response.available()).isTrue();
        verify(userRepository).existsByHandle("test.user");
    }

    @Test
    void signUpCreatesLocalUserAndIssuesTokens() {
        UUID userId = UUID.randomUUID();
        SignUpRequest request = new SignUpRequest(
                "Test.User",
                "  Tester  ",
                " Test@Example.com ",
                "Password1!",
                "Password1!",
                true,
                true
        );
        TokenService.AuthResult authResult = authResult(userId, "test.user", "Tester");

        when(userRepository.existsByHandle("test.user")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", userId);
            return user;
        });
        when(tokenService.issueTokens(any(User.class))).thenReturn(authResult);

        TokenService.AuthResult result = localAuthService.signUp(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getHandle()).isEqualTo("test.user");
        assertThat(savedUser.getNickname()).isEqualTo("Tester");
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(result).isEqualTo(authResult);
    }

    @Test
    void signUpRejectsDuplicateEmail() {
        SignUpRequest request = new SignUpRequest(
                "tester",
                "Tester",
                "test@example.com",
                "Password1!",
                "Password1!",
                true,
                true
        );

        when(userRepository.existsByHandle("tester")).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> localAuthService.signUp(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());

        verify(userRepository, never()).save(any(User.class));
        verify(tokenService, never()).issueTokens(any(User.class));
    }

    @Test
    void socialSignUpCreatesUserSocialAccountAndDeletesSession() {
        UUID userId = UUID.randomUUID();
        SocialSignUpRequest request = new SocialSignUpRequest(
                "signup-token",
                "social_user",
                "Social Nick",
                true,
                true
        );
        SocialSignupSession session = SocialSignupSession.create(
                "google",
                "provider-user-id",
                "social@example.com",
                "Social User",
                "hashed-signup-token",
                30
        );
        TokenService.AuthResult authResult = authResult(userId, "social_user", "Social Nick");

        when(tokenService.sha256("signup-token")).thenReturn("hashed-signup-token");
        when(socialSignupSessionRepository.findBySignupTokenHash("hashed-signup-token"))
                .thenReturn(Optional.of(session));
        when(userRepository.existsByHandle("social_user")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", userId);
            return user;
        });
        when(tokenService.issueTokens(any(User.class))).thenReturn(authResult);

        TokenService.AuthResult result = localAuthService.socialSignUp(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<SocialAccount> socialAccountCaptor = ArgumentCaptor.forClass(SocialAccount.class);

        verify(userRepository).save(userCaptor.capture());
        verify(socialAccountRepository).save(socialAccountCaptor.capture());
        verify(socialSignupSessionRepository).delete(session);

        User savedUser = userCaptor.getValue();
        SocialAccount savedSocialAccount = socialAccountCaptor.getValue();

        assertThat(savedUser.getEmail()).isEqualTo("social@example.com");
        assertThat(savedUser.getHandle()).isEqualTo("social_user");
        assertThat(savedUser.getNickname()).isEqualTo("Social Nick");
        assertThat(savedUser.getPasswordHash()).isNull();
        assertThat(savedSocialAccount.getUserId()).isEqualTo(userId);
        assertThat(result).isEqualTo(authResult);
    }

    @Test
    void socialSignUpRejectsExpiredSessionAndDeletesIt() {
        SocialSignUpRequest request = new SocialSignUpRequest(
                "signup-token",
                "social_user",
                "Social Nick",
                true,
                true
        );
        SocialSignupSession expiredSession = SocialSignupSession.create(
                "google",
                "provider-user-id",
                "social@example.com",
                "Social User",
                "hashed-signup-token",
                -1
        );

        when(tokenService.sha256("signup-token")).thenReturn("hashed-signup-token");
        when(socialSignupSessionRepository.findBySignupTokenHash("hashed-signup-token"))
                .thenReturn(Optional.of(expiredSession));

        assertThatThrownBy(() -> localAuthService.socialSignUp(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        verify(socialSignupSessionRepository).delete(expiredSession);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginUpdatesLastLoginAtAndIssuesTokens() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester@example.com", "tester", "Tester", "encoded-password");
        TokenService.AuthResult authResult = authResult(userId, "tester", "Tester");

        when(userRepository.findByHandle("tester")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password1!", "encoded-password")).thenReturn(true);
        when(tokenService.issueTokens(user)).thenReturn(authResult);

        TokenService.AuthResult result = localAuthService.login(new LoginRequest("tester", "Password1!"));

        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(result).isEqualTo(authResult);
    }

    @Test
    void loginRejectsInactiveUser() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);

        when(userRepository.findByHandle("tester")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> localAuthService.login(new LoginRequest("tester", "Password1!")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void refreshRevokesStoredTokenAndIssuesNewTokens() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester@example.com", "tester", "Tester", "encoded-password");
        RefreshToken refreshToken = RefreshToken.issue(
                userId,
                "hashed-refresh-token",
                OffsetDateTime.now().plusDays(1),
                null,
                null
        );
        TokenService.AuthResult authResult = authResult(userId, "tester", "Tester");

        when(tokenService.sha256("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull("hashed-refresh-token"))
                .thenReturn(Optional.of(refreshToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenService.issueTokens(user)).thenReturn(authResult);

        TokenService.AuthResult result = localAuthService.refresh("raw-refresh-token");

        assertThat(refreshToken.isRevoked()).isTrue();
        assertThat(result).isEqualTo(authResult);
    }

    @Test
    void findIdReturnsMaskedHandleAndCreatedAt() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-04-20T10:15:30+09:00");
        User user = savedUser(userId, "user@example.com", "abcdef", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "createdAt", createdAt);

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        FindIdResponse response = localAuthService.findId(new FindIdRequest("User@Example.com"));

        assertThat(response.maskedHandle()).isEqualTo("abc***");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void findPasswordCreatesResetTokenAndSendsMail() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "User@Example.com", "tester", "Tester", "encoded-password");
        PasswordResetToken existingToken = PasswordResetToken.issue(
                userId,
                "old-hash",
                OffsetDateTime.now().plusMinutes(10)
        );

        when(userRepository.findByHandle("tester")).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.findAllByUserIdAndUsedAtIsNull(userId)).thenReturn(List.of(existingToken));
        when(tokenService.generateOpaqueToken()).thenReturn("raw-reset-token");
        when(tokenService.sha256("raw-reset-token")).thenReturn("hashed-reset-token");
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(passwordResetMailService).sendPasswordResetMail("user@example.com", "raw-reset-token");

        localAuthService.findPassword(new FindPasswordRequest("tester"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        verify(passwordResetMailService).sendPasswordResetMail("user@example.com", "raw-reset-token");

        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertThat(existingToken.isUsed()).isTrue();
        assertThat(savedToken.getUserId()).isEqualTo(userId);
        assertThat(savedToken.getTokenHash()).isEqualTo("hashed-reset-token");
        assertThat(savedToken.getExpiresAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void findPasswordDoesNothingWhenUserDoesNotExist() {
        when(userRepository.findByHandle("missing")).thenReturn(Optional.empty());

        localAuthService.findPassword(new FindPasswordRequest("missing"));

        verifyNoInteractions(passwordResetTokenRepository);
        verifyNoInteractions(passwordResetMailService);
    }

    @Test
    void resetPasswordChangesStoredPasswordHashAndRevokesActiveRefreshTokens() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "user@example.com", "tester", "Tester", "encoded-password");
        PasswordResetToken passwordResetToken = PasswordResetToken.issue(
                userId,
                "hashed-reset-token",
                OffsetDateTime.now().plusMinutes(10)
        );
        RefreshToken refreshToken = RefreshToken.issue(
                userId,
                "hashed-refresh-token",
                OffsetDateTime.now().plusDays(1),
                null,
                null
        );

        when(tokenService.sha256("raw-reset-token")).thenReturn("hashed-reset-token");
        when(passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull("hashed-reset-token"))
                .thenReturn(Optional.of(passwordResetToken));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPassword1!")).thenReturn("new-encoded-password");
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(refreshToken));

        localAuthService.resetPassword(new ResetPasswordRequest("raw-reset-token", "NewPassword1!", "NewPassword1!"));

        assertThat(user.getPasswordHash()).isEqualTo("new-encoded-password");
        assertThat(passwordResetToken.isUsed()).isTrue();
        assertThat(refreshToken.isRevoked()).isTrue();
    }

    @Test
    void resetPasswordRejectsExpiredResetToken() {
        UUID userId = UUID.randomUUID();
        PasswordResetToken expiredToken = PasswordResetToken.issue(
                userId,
                "expired-reset-token",
                OffsetDateTime.now().minusMinutes(1)
        );

        when(tokenService.sha256("raw-reset-token")).thenReturn("expired-reset-token");
        when(passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull("expired-reset-token"))
                .thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> localAuthService.resetPassword(
                new ResetPasswordRequest("raw-reset-token", "NewPassword1!", "NewPassword1!")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        assertThat(expiredToken.isUsed()).isTrue();
    }

    @Test
    void logoutRevokesRefreshTokenWhenFound() {
        UUID userId = UUID.randomUUID();
        RefreshToken refreshToken = RefreshToken.issue(
                userId,
                "hashed-refresh-token",
                OffsetDateTime.now().plusDays(1),
                null,
                null
        );

        when(tokenService.sha256("raw-refresh-token")).thenReturn("hashed-refresh-token");
        when(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull("hashed-refresh-token"))
                .thenReturn(Optional.of(refreshToken));

        localAuthService.logout("raw-refresh-token");

        assertThat(refreshToken.isRevoked()).isTrue();
    }

    private User savedUser(UUID id, String email, String handle, String nickname, String passwordHash) {
        User user = User.createLocal(email, handle, nickname, passwordHash);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private TokenService.AuthResult authResult(UUID userId, String handle, String nickname) {
        return new TokenService.AuthResult(
                new AuthTokenResponse(userId, handle, nickname, "access-token", 1800L),
                "refresh-token",
                1209600L
        );
    }
}
