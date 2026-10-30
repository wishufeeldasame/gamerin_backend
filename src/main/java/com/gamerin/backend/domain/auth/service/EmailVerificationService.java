package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.gamerin.backend.domain.auth.dto.response.EmailVerificationResponse;
import com.gamerin.backend.domain.auth.entity.EmailVerification;
import com.gamerin.backend.domain.auth.entity.EmailVerificationLock;
import com.gamerin.backend.domain.auth.repository.EmailVerificationLockRepository;
import com.gamerin.backend.domain.auth.repository.EmailVerificationRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;

@Service
@Transactional
public class EmailVerificationService {
    static final long CODE_EXPIRATION_SECONDS = 5 * 60;
    static final long TOKEN_EXPIRATION_SECONDS = 30 * 60;
    static final long RESEND_COOLDOWN_SECONDS = 60;
    static final int MAX_ATTEMPTS = 5;

    private final EmailVerificationRepository emailVerificationRepository;
    private final EmailVerificationLockRepository emailVerificationLockRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final EmailVerificationMailService emailVerificationMailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
            EmailVerificationRepository emailVerificationRepository,
            EmailVerificationLockRepository emailVerificationLockRepository,
            UserRepository userRepository,
            TokenService tokenService,
            EmailVerificationMailService emailVerificationMailService) {
        this.emailVerificationRepository = emailVerificationRepository;
        this.emailVerificationLockRepository = emailVerificationLockRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.emailVerificationMailService = emailVerificationMailService;
    }

    public EmailVerificationResponse sendSignupCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        lockEmailForSend(email);

        OffsetDateTime now = OffsetDateTime.now();
        emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(email)
                .filter(latest -> latest.getCreatedAt().isAfter(now.minusSeconds(RESEND_COOLDOWN_SECONDS)))
                .ifPresent(latest -> {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분 후에 다시 요청할 수 있습니다.");
                });

        // 최신 발송 건만 확인 대상이므로 이전 인증번호는 자동으로 무효가 된다
        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        emailVerificationRepository.save(EmailVerification.issue(
                email, tokenService.sha256(code), now.plusSeconds(CODE_EXPIRATION_SECONDS)));
        emailVerificationMailService.sendSignupVerificationCode(email, code);

        return new EmailVerificationResponse(CODE_EXPIRATION_SECONDS, RESEND_COOLDOWN_SECONDS);
    }

    // 발송 이력이 없는 이메일은 잠글 행이 없으므로 잠금 행을 먼저 만들고, 동시 최초 요청은 PK 충돌로 거절한다
    private void lockEmailForSend(String email) {
        if (emailVerificationLockRepository.findByEmail(email).isPresent()) {
            return;
        }
        try {
            emailVerificationLockRepository.saveAndFlush(EmailVerificationLock.of(email));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 1분 후에 다시 요청할 수 있습니다.");
        }
    }

    // 틀린 시도 횟수가 예외와 함께 롤백되지 않도록 커밋한다
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EmailVerificationConfirmResponse confirmSignupCode(String rawEmail, String code) {
        String email = normalizeEmail(rawEmail);
        EmailVerification verification = emailVerificationRepository.findTopByEmailOrderByCreatedAtDesc(email)
                .filter(latest -> !latest.isVerified() && !latest.isExpired())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "인증번호가 만료되었거나 존재하지 않습니다. 인증번호를 다시 요청해주세요."));

        if (verification.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "인증번호 입력 횟수를 초과했습니다. 인증번호를 다시 요청해주세요.");
        }

        if (!MessageDigest.isEqual(
                verification.getCodeHash().getBytes(StandardCharsets.UTF_8),
                tokenService.sha256(code).getBytes(StandardCharsets.UTF_8))) {
            verification.recordFailedAttempt();
            int remaining = MAX_ATTEMPTS - verification.getAttemptCount();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "인증번호가 일치하지 않습니다. (남은 횟수: " + remaining + "회)");
        }

        String verificationToken = tokenService.generateOpaqueToken();
        verification.markVerified(
                tokenService.sha256(verificationToken),
                OffsetDateTime.now().plusSeconds(TOKEN_EXPIRATION_SECONDS));
        return new EmailVerificationConfirmResponse(verificationToken, TOKEN_EXPIRATION_SECONDS);
    }

    // 가입 트랜잭션에 참여하므로 가입이 실패하면 사용 처리도 함께 롤백된다
    public void consumeSignupToken(String email, String rawVerificationToken) {
        EmailVerification verification = emailVerificationRepository
                .findByVerificationTokenHashAndConsumedAtIsNull(tokenService.sha256(rawVerificationToken))
                .filter(found -> found.getEmail().equals(email) && !found.isExpired())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "이메일 인증이 만료되었거나 유효하지 않습니다. 이메일 인증을 다시 진행해주세요."));
        verification.consume();
    }

    private String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일이 비어 있습니다.");
        }
        return rawEmail.trim().toLowerCase();
    }
}
