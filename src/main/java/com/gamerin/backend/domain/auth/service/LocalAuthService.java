package com.gamerin.backend.domain.auth.service;

import com.gamerin.backend.domain.auth.dto.request.FindIdRequest;
import com.gamerin.backend.domain.auth.dto.response.FindIdResponse;
import com.gamerin.backend.domain.auth.dto.request.LoginRequest;
import com.gamerin.backend.domain.auth.dto.request.SignUpRequest;
import com.gamerin.backend.domain.auth.dto.request.SocialSignUpRequest;
import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.dto.response.HandleAvailabilityResponse;
import com.gamerin.backend.domain.auth.dto.response.MeResponse;
import com.gamerin.backend.domain.auth.entity.RefreshToken;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.gamerin.backend.domain.auth.dto.request.FindPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.ResetPasswordRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@Transactional
public class LocalAuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTokenExpirationSeconds;

    public LocalAuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${app.auth.jwt.refresh-token-expiration-seconds:1209600}") long refreshTokenExpirationSeconds
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    @Transactional(readOnly = true)
    public HandleAvailabilityResponse checkHandleAvailability(String rawHandle) {
        String handle = normalizeHandle(rawHandle);
        return new HandleAvailabilityResponse(handle, !userRepository.existsByHandle(handle));
    }

    public AuthResult signUp(SignUpRequest request) {
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
        User user = User.createLocal(
            email,
            handle,
            request.nickname().trim(),
            encodedPassword
        );
        User savedUser = userRepository.save(user);

        return issueTokens(savedUser);
    }

    @Transactional(readOnly = true)
    public FindIdResponse findId(FindIdRequest request) {
        String email = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "일치하는 계정을 찾을 수 없습니다."
                ));

        return new FindIdResponse(maskHandle(user.getHandle()));
    }

    @Transactional(readOnly = true)
    public void findPassword(FindPasswordRequest request) {
        String handle = normalizeHandle(request.handle());

        userRepository.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "일치하는 계정을 찾을 수 없습니다."
                ));
    }

    private String maskHandle(String handle) {
        if (handle == null || handle.length() <= 3) {
            return handle;
        }

        return handle.substring(0, 3) + "*".repeat(handle.length() - 3);
    }

    @Transactional
    public AuthResult socialSignUp(SocialSignUpRequest request) {
        io.jsonwebtoken.Claims claims = jwtTokenProvider.parseRegisterToken(request.registerToken());
        
        if (!"REGISTER".equals(claims.get("type"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 가입 토큰입니다.");
        }

        String email = claims.getSubject();
        String provider = claims.get("provider", String.class);
        String providerId = claims.get("providerId", String.class);

        String handle = normalizeHandle(request.handle());
        if (userRepository.existsByHandle(handle)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디(핸들)입니다.");
        }

        // 비밀번호 없이(null) 소셜 계정 생성
        User user = User.createSocial(email, handle, request.nickname().trim(), provider, providerId);
        User savedUser = userRepository.save(user);

        return issueTokens(savedUser); // public으로 변경한 토큰 발급 메서드 재사용
    }

    public AuthResult login(LoginRequest request) {
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
        return issueTokens(user);
    }
    
    public void resetPassword(ResetPasswordRequest request) {
        String handle = normalizeHandle(request.handle());

        User user = userRepository.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "일치하는 계정을 찾을 수 없습니다."
                ));

        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "비밀번호 확인이 일치하지 않습니다."
            );
        }

        String encodedPassword = passwordEncoder.encode(request.newPassword());
        user.changePassword(encodedPassword);
    }

    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 없습니다.");
        }

        String tokenHash = sha256(rawRefreshToken);
        RefreshToken savedToken = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."));

        if (savedToken.isExpired() || savedToken.isRevoked()) {
            savedToken.revoke();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "만료된 리프레시 토큰입니다.");
        }

        User user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        savedToken.revoke();
        return issueTokens(user);
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        String tokenHash = sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(CustomUserPrincipal principal) {
        return new MeResponse(
                principal.getUserId(),
                principal.getUsername(),
                principal.getNickname(),
                principal.getAuthorities().stream().findFirst().map(Object::toString).orElse("ROLE_USER"),
                "ACTIVE"
        );
    }

    public AuthResult issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                user.getRole() == null ? java.util.List.of("USER") : java.util.List.of(user.getRole().name())
        );

        String refreshToken = generateOpaqueToken();
        RefreshToken refreshTokenEntity = RefreshToken.issue(
                user.getId(),
                sha256(refreshToken),
                OffsetDateTime.now().plusSeconds(refreshTokenExpirationSeconds)
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

    private String generateOpaqueToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
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

    public record AuthResult(
            AuthTokenResponse authTokenResponse,
            String refreshToken,
            long refreshTokenExpiresIn
    ) {
    }
}
