package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.gamerin.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.gamerin.backend.domain.auth.entity.EmailVerification;
import com.gamerin.backend.domain.auth.repository.EmailVerificationRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private EmailVerificationMailService emailVerificationMailService;

    private EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        emailVerificationService = new EmailVerificationService(
                emailVerificationRepository, userRepository, tokenService, emailVerificationMailService);
        lenient().when(tokenService.sha256(anyString())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
    }

    @Test
    void sendSignupCodeStoresHashedCodeAndMailsRawCode() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL))
                .thenReturn(Optional.of(verification(OffsetDateTime.now().minusSeconds(61))));

        EmailVerificationResponse response = emailVerificationService.sendSignupCode(" User@Example.com ");

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmailVerification> savedCaptor = ArgumentCaptor.forClass(EmailVerification.class);
        verify(emailVerificationMailService).sendSignupVerificationCode(eq(EMAIL), codeCaptor.capture());
        verify(emailVerificationRepository).save(savedCaptor.capture());

        String code = codeCaptor.getValue();
        EmailVerification saved = savedCaptor.getValue();
        assertThat(code).matches("\\d{6}");
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getCodeHash()).isEqualTo("hash:" + code);
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(4));
        assertThat(response.expiresInSeconds()).isEqualTo(300);
        assertThat(response.resendCooldownSeconds()).isEqualTo(60);
    }

    @Test
    void sendSignupCodeRejectsRegisteredEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertStatus(() -> emailVerificationService.sendSignupCode(EMAIL), HttpStatus.CONFLICT);

        verifyNoInteractions(emailVerificationRepository, emailVerificationMailService);
    }

    @Test
    void sendSignupCodeRejectsResendWithinCooldown() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL))
                .thenReturn(Optional.of(verification(OffsetDateTime.now().minusSeconds(10))));

        assertStatus(() -> emailVerificationService.sendSignupCode(EMAIL), HttpStatus.TOO_MANY_REQUESTS);

        verify(emailVerificationRepository, never()).save(any(EmailVerification.class));
        verifyNoInteractions(emailVerificationMailService);
    }

    @Test
    void confirmSignupCodeIssuesVerificationToken() {
        EmailVerification verification = verification(OffsetDateTime.now());
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(verification));
        when(tokenService.generateOpaqueToken()).thenReturn("raw-token");

        EmailVerificationConfirmResponse response = emailVerificationService.confirmSignupCode(EMAIL, "123456");

        assertThat(response.verificationToken()).isEqualTo("raw-token");
        assertThat(response.expiresInSeconds()).isEqualTo(1800);
        assertThat(verification.isVerified()).isTrue();
        assertThat(verification.getExpiresAt()).isAfter(OffsetDateTime.now().plusMinutes(29));
    }

    @Test
    void confirmSignupCodeCountsWrongAttempt() {
        EmailVerification verification = verification(OffsetDateTime.now());
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> emailVerificationService.confirmSignupCode(EMAIL, "000000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("남은 횟수: 4회");

        assertThat(verification.getAttemptCount()).isEqualTo(1);
        assertThat(verification.isVerified()).isFalse();
    }

    @Test
    void confirmSignupCodeRejectsCorrectCodeAfterMaxAttempts() {
        EmailVerification verification = verification(OffsetDateTime.now());
        ReflectionTestUtils.setField(verification, "attemptCount", EmailVerificationService.MAX_ATTEMPTS);
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertStatus(() -> emailVerificationService.confirmSignupCode(EMAIL, "123456"), HttpStatus.BAD_REQUEST);

        assertThat(verification.isVerified()).isFalse();
    }

    @Test
    void confirmSignupCodeRejectsExpiredCode() {
        EmailVerification verification = verification(OffsetDateTime.now().minusMinutes(6));
        ReflectionTestUtils.setField(verification, "expiresAt", OffsetDateTime.now().minusMinutes(1));
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertStatus(() -> emailVerificationService.confirmSignupCode(EMAIL, "123456"), HttpStatus.BAD_REQUEST);

        assertThat(verification.isVerified()).isFalse();
    }

    @Test
    void confirmSignupCodeRejectsAlreadyVerifiedCode() {
        EmailVerification verification = verifiedVerification(EMAIL, OffsetDateTime.now().plusMinutes(30));
        when(emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertStatus(() -> emailVerificationService.confirmSignupCode(EMAIL, "123456"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void consumeSignupTokenMarksVerificationConsumed() {
        EmailVerification verification = verifiedVerification(EMAIL, OffsetDateTime.now().plusMinutes(30));
        when(emailVerificationRepository.findByVerificationTokenHashAndConsumedAtIsNull("hash:raw-token"))
                .thenReturn(Optional.of(verification));

        emailVerificationService.consumeSignupToken(EMAIL, "raw-token");

        assertThat(verification.isConsumed()).isTrue();
    }

    @Test
    void consumeSignupTokenRejectsTokenIssuedForOtherEmail() {
        EmailVerification verification = verifiedVerification("other@example.com", OffsetDateTime.now().plusMinutes(30));
        when(emailVerificationRepository.findByVerificationTokenHashAndConsumedAtIsNull("hash:raw-token"))
                .thenReturn(Optional.of(verification));

        assertStatus(() -> emailVerificationService.consumeSignupToken(EMAIL, "raw-token"), HttpStatus.BAD_REQUEST);

        assertThat(verification.isConsumed()).isFalse();
    }

    @Test
    void consumeSignupTokenRejectsExpiredToken() {
        EmailVerification verification = verifiedVerification(EMAIL, OffsetDateTime.now().minusMinutes(1));
        when(emailVerificationRepository.findByVerificationTokenHashAndConsumedAtIsNull("hash:raw-token"))
                .thenReturn(Optional.of(verification));

        assertStatus(() -> emailVerificationService.consumeSignupToken(EMAIL, "raw-token"), HttpStatus.BAD_REQUEST);

        assertThat(verification.isConsumed()).isFalse();
    }

    @Test
    void consumeSignupTokenRejectsUnknownOrUsedToken() {
        when(emailVerificationRepository.findByVerificationTokenHashAndConsumedAtIsNull("hash:raw-token"))
                .thenReturn(Optional.empty());

        assertStatus(() -> emailVerificationService.consumeSignupToken(EMAIL, "raw-token"), HttpStatus.BAD_REQUEST);
    }

    private EmailVerification verification(OffsetDateTime createdAt) {
        EmailVerification verification = EmailVerification.issue(
                EMAIL, "hash:123456", createdAt.plusSeconds(EmailVerificationService.CODE_EXPIRATION_SECONDS));
        ReflectionTestUtils.setField(verification, "createdAt", createdAt);
        return verification;
    }

    private EmailVerification verifiedVerification(String email, OffsetDateTime tokenExpiresAt) {
        EmailVerification verification = EmailVerification.issue(email, "hash:123456", OffsetDateTime.now().plusMinutes(5));
        ReflectionTestUtils.setField(verification, "createdAt", OffsetDateTime.now());
        verification.markVerified("hash:raw-token", tokenExpiresAt);
        return verification;
    }

    private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, HttpStatus status) {
        assertThatThrownBy(callable)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(status.value());
    }
}
