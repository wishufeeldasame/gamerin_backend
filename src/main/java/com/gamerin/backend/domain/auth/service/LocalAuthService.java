package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.request.*;
import com.gamerin.backend.domain.auth.dto.response.*;
import com.gamerin.backend.domain.auth.entity.PasswordResetToken;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.entity.SocialAccount;
import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.repository.PasswordResetTokenRepository;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Transactional
public class LocalAuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final SocialSignupSessionRepository socialSignupSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final PasswordResetMailService passwordResetMailService;
    private final long passwordResetExpirationMinutes;
    private final LoginFailureTracker loginFailureTracker = new LoginFailureTracker();

    public LocalAuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            SocialAccountRepository socialAccountRepository,
            SocialSignupSessionRepository socialSignupSessionRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            PasswordResetMailService passwordResetMailService,
            @Value("${app.auth.password-reset.expiration-minutes:30}") long passwordResetExpirationMinutes
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.socialSignupSessionRepository = socialSignupSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.passwordResetMailService = passwordResetMailService;
        this.passwordResetExpirationMinutes = passwordResetExpirationMinutes;
    }

    @Transactional(readOnly = true)
    public HandleAvailabilityResponse checkHandleAvailability(String rawHandle) {
        String handle = normalizeHandle(rawHandle);
        return new HandleAvailabilityResponse(handle, !userRepository.existsByHandle(handle));
    }

    public TokenService.AuthResult signUp(SignUpRequest request) {
        validatePasswordConfirmation(request.password(), request.passwordConfirm());

        String handle = normalizeHandle(request.handle());
        String email = normalizeEmail(request.email());

        if (userRepository.existsByHandle(handle)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.createLocal(email, handle, request.nickname().trim(), encodedPassword);
        user.setProfile(UserProfile.createDefault(user));
        User savedUser = userRepository.save(user);

        return tokenService.issueTokens(savedUser); // 토큰 공장 호출
    }

    public TokenService.AuthResult socialSignUp(SocialSignUpRequest request) {
        String tokenHash = tokenService.sha256(request.signupToken());
        SocialSignupSession session = socialSignupSessionRepository.findBySignupTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 가입 토큰입니다."));

        if (session.isExpired()) {
            socialSignupSessionRepository.delete(session);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료된 가입 토큰입니다.");
        }

        String handle = normalizeHandle(request.handle());
        if (userRepository.existsByHandle(handle)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디(핸들)입니다.");
        }

        User user = User.createSocialOnly(session.getProviderEmail(), handle, request.nickname().trim());
        user.setProfile(UserProfile.createDefault(user));
        User savedUser = userRepository.save(user);

        SocialAccount socialAccount = SocialAccount.create(
                savedUser.getId(), session.getProvider(), session.getProviderUserId(),
                session.getProviderEmail(), session.getProviderDisplayName()
        );
        socialAccountRepository.save(socialAccount);
        socialSignupSessionRepository.delete(session);

        return tokenService.issueTokens(savedUser);
    }

    public TokenService.AuthResult login(LoginRequest request) {
        String handle = normalizeHandle(request.handle());
        
        loginFailureTracker.checkLockout(handle);

        User user = userRepository.findByHandle(handle)
                .orElseThrow(() -> {
                    loginFailureTracker.recordFailure(handle);
                    int remaining = loginFailureTracker.getRemainingAttempts(handle);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, 
                            "아이디 또는 비밀번호가 올바르지 않습니다. (5회 연속 실패 시 잠금, 남은 횟수: " + remaining + "회)");
                });

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 상태 계정이 아닙니다.");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailureTracker.recordFailure(handle);
            int remaining = loginFailureTracker.getRemainingAttempts(handle);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, 
                    "아이디 또는 비밀번호가 올바르지 않습니다. (5회 연속 실패 시 잠금, 남은 횟수: " + remaining + "회)");
        }

        loginFailureTracker.resetFailures(handle);

        user.updateLastLoginAt();
        return tokenService.issueTokens(user);
    }

    public TokenService.AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 없습니다.");
        }

        String tokenHash = tokenService.sha256(rawRefreshToken);
        RefreshToken savedToken = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."));

        if (savedToken.isExpired() || savedToken.isRevoked()) {
            savedToken.revoke();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다.");
        }

        User user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        savedToken.revoke();
        return tokenService.issueTokens(user);
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String tokenHash = tokenService.sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public FindIdResponse findId(FindIdRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."));
        return new FindIdResponse(maskHandle(user.getHandle()), user.getCreatedAt());
    }

    public void findPassword(FindPasswordRequest request) {
        String handle = normalizeHandle(request.handle());
        userRepository.findByHandle(handle).ifPresent(this::issuePasswordResetToken);
    }

    public void resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호 확인이 일치하지 않습니다.");
        }

        String tokenHash = tokenService.sha256(request.resetToken());
        PasswordResetToken passwordResetToken = passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 비밀번호 재설정 토큰입니다."));

        if (passwordResetToken.isExpired()) {
            passwordResetToken.use();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료된 비밀번호 재설정 토큰입니다.");
        }

        User user = userRepository.findById(passwordResetToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."));

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        passwordResetToken.use();
        revokeActiveRefreshTokens(user.getId());
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(CustomUserPrincipal principal) {
        return new MeResponse(
                principal.getUserId(), principal.getUsername(), principal.getNickname(),
                principal.getAuthorities().stream().findFirst().map(Object::toString).orElse("ROLE_USER"),
                "ACTIVE"
        );
    }

    private void validatePasswordConfirmation(String password, String passwordConfirm) {
        if (!password.equals(passwordConfirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호 확인이 일치하지 않습니다.");
        }
    }

    private String normalizeHandle(String rawHandle) {
        if (rawHandle == null || rawHandle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "아이디가 비어 있습니다.");
        }
        return rawHandle.trim().toLowerCase();
    }

    private String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일이 비어 있습니다.");
        }
        return rawEmail.trim().toLowerCase();
    }

    private void issuePasswordResetToken(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }

        String email = normalizeEmail(user.getEmail());
        invalidateActivePasswordResetTokens(user.getId());

        String rawResetToken = tokenService.generateOpaqueToken();
        PasswordResetToken passwordResetToken = PasswordResetToken.issue(
                user.getId(),
                tokenService.sha256(rawResetToken),
                OffsetDateTime.now().plusMinutes(passwordResetExpirationMinutes)
        );

        passwordResetTokenRepository.save(passwordResetToken);
        passwordResetMailService.sendPasswordResetMail(email, rawResetToken);
    }

    private void invalidateActivePasswordResetTokens(UUID userId) {
        passwordResetTokenRepository.findAllByUserIdAndUsedAtIsNull(userId)
                .forEach(PasswordResetToken::use);
    }

    private void revokeActiveRefreshTokens(UUID userId) {
        refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)
                .forEach(RefreshToken::revoke);
    }

    private String maskHandle(String handle) {
        if (handle == null || handle.length() <= 3) return handle;
        return handle.substring(0, 3) + "*".repeat(handle.length() - 3);
    }

    private static class LoginFailureTracker {
        private static final int MAX_ATTEMPTS = 5;
        private static final int LOCKOUT_DURATION_MINUTES = 15;

        private static class FailureInfo {
            int count;
            java.time.Instant lockoutUntil;

            FailureInfo() {
                this.count = 0;
                this.lockoutUntil = null;
            }
        }

        private final java.util.concurrent.ConcurrentHashMap<String, FailureInfo> failureMap = 
                new java.util.concurrent.ConcurrentHashMap<>();

        public void checkLockout(String handle) {
            FailureInfo info = failureMap.get(handle);
            if (info != null && info.lockoutUntil != null) {
                if (info.lockoutUntil.isAfter(java.time.Instant.now())) {
                    long minutesLeft = java.time.Duration.between(java.time.Instant.now(), info.lockoutUntil).toMinutes() + 1;
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.FORBIDDEN,
                            "반복된 로그인 실패로 계정이 임시 잠금되었습니다. " + minutesLeft + "분 후 다시 시도해 주세요."
                    );
                } else {
                    failureMap.remove(handle);
                }
            }
        }

        public void recordFailure(String handle) {
            FailureInfo info = failureMap.computeIfAbsent(handle, k -> new FailureInfo());
            info.count++;
            if (info.count >= MAX_ATTEMPTS) {
                info.lockoutUntil = java.time.Instant.now().plusSeconds(LOCKOUT_DURATION_MINUTES * 60L);
            }
        }

        public int getRemainingAttempts(String handle) {
            FailureInfo info = failureMap.get(handle);
            if (info == null) {
                return MAX_ATTEMPTS;
            }
            return Math.max(0, MAX_ATTEMPTS - info.count);
        }

        public void resetFailures(String handle) {
            failureMap.remove(handle);
        }
    }
}
