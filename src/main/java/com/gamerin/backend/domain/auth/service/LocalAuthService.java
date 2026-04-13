package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.request.*;
import com.gamerin.backend.domain.auth.dto.response.*;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.entity.SocialAccount;
import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class LocalAuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final SocialSignupSessionRepository socialSignupSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService; // TokenService 주입!

    public LocalAuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            SocialAccountRepository socialAccountRepository,
            SocialSignupSessionRepository socialSignupSessionRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.socialSignupSessionRepository = socialSignupSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional(readOnly = true)
    public HandleAvailabilityResponse checkHandleAvailability(String rawHandle) {
        String handle = normalizeHandle(rawHandle);
        return new HandleAvailabilityResponse(handle, !userRepository.existsByHandle(handle));
    }

    public TokenService.AuthResult signUp(SignUpRequest request) {
        validatePasswordConfirmation(request.password(), request.passwordConfirm());

        String handle = normalizeHandle(request.handle());
        String email = request.email().trim();

        if (userRepository.existsByHandle(handle)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.createLocal(email, handle, request.nickname().trim(), encodedPassword);
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
        User savedUser = userRepository.save(user);

        SocialAccount socialAccount = SocialAccount.create(
                savedUser.getId(), session.getProvider(), session.getProviderUserId(),
                session.getProviderEmail(), session.getProviderDisplayName()
        );
        socialAccountRepository.save(socialAccount);
        socialSignupSessionRepository.delete(session);

        return tokenService.issueTokens(savedUser); // 토큰 공장 호출
    }

    public TokenService.AuthResult login(LoginRequest request) {
        String handle = normalizeHandle(request.handle());
        User user = userRepository.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 상태 계정이 아닙니다.");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        user.updateLastLoginAt();
        return tokenService.issueTokens(user); // 토큰 공장 호출
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
        return tokenService.issueTokens(user); // 토큰 공장 호출
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String tokenHash = tokenService.sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public FindIdResponse findId(FindIdRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."));
        return new FindIdResponse(maskHandle(user.getHandle()), user.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public void findPassword(FindPasswordRequest request) {
        String handle = normalizeHandle(request.handle());
        userRepository.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."));
    }

    public void resetPassword(ResetPasswordRequest request) {
        String handle = normalizeHandle(request.handle());
        User user = userRepository.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."));

        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호 확인이 일치하지 않습니다.");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
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

    private String maskHandle(String handle) {
        if (handle == null || handle.length() <= 3) return handle;
        return handle.substring(0, 3) + "*".repeat(handle.length() - 3);
    }
}