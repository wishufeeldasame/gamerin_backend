package com.gamerin.backend.global.security.oauth2;

import com.gamerin.backend.domain.auth.entity.SocialAccount;
import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Optional;

@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final SocialAccountRepository socialAccountRepository;
    private final SocialSignupSessionRepository socialSignupSessionRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    
    private final String frontendBaseUrl;
    private final String refreshCookieName;
    private final boolean refreshCookieSecure;
    private final String refreshCookieSameSite;

    public OAuth2SuccessHandler(
            SocialAccountRepository socialAccountRepository,
            SocialSignupSessionRepository socialSignupSessionRepository,
            UserRepository userRepository,
            TokenService tokenService,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.auth.jwt.refresh-cookie-name:refresh_token}") String refreshCookieName,
            @Value("${app.auth.jwt.refresh-cookie-secure:false}") boolean refreshCookieSecure,
            @Value("${app.auth.jwt.refresh-cookie-same-site:Lax}") String refreshCookieSameSite
    ) {
        this.socialAccountRepository = socialAccountRepository;
        this.socialSignupSessionRepository = socialSignupSessionRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.refreshCookieName = refreshCookieName;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshCookieSameSite = normalizeSameSite(refreshCookieSameSite);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;

        String provider = oauthToken.getAuthorizedClientRegistrationId().toUpperCase();
        String providerUserId = oAuth2User.getAttribute("sub"); 
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");

        // 1. 소셜 계정 정보가 이미 연동되어 있는지 확인
        Optional<SocialAccount> accountOpt = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);

        String targetUrl;
        if (accountOpt.isPresent()) {
            // [Case A: 이미 소셜 연동이 완료된 기존 유저] -> 바로 로그인
            SocialAccount socialAccount = accountOpt.get();
            User user = userRepository.findById(socialAccount.getUserId())
                    .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));

            processLogin(user, socialAccount, response);

            TokenService.AuthResult result = tokenService.issueTokens(user);
            setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
            targetUrl = buildLoginSuccessUrl(result.authTokenResponse().accessToken());

        } else {
            // [Case B: 소셜 연동 정보가 없음] -> 이메일 중복 체크를 통해 통합 가입 시도
            Optional<User> existingUserOpt = userRepository.findByEmail(email);

            if (existingUserOpt.isPresent()) {
                // [Case B-1: 동일한 이메일을 가진 일반 유저가 이미 존재함] -> 즉시 연동 및 로그인
                User user = existingUserOpt.get();

                SocialAccount newAccount = SocialAccount.create(
                        user.getId(), provider, providerUserId, email, name
                );
                socialAccountRepository.save(newAccount);

                processLogin(user, newAccount, response);

                TokenService.AuthResult result = tokenService.issueTokens(user);
                setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
                targetUrl = buildLoginSuccessUrl(result.authTokenResponse().accessToken());

            } else {
                // [Case B-2: 서비스에 아예 처음 온 완전 신규 유저] -> 가입 세션 열고 폼 페이지로 리다이렉트
                String signupToken = tokenService.generateOpaqueToken();
                SocialSignupSession session = SocialSignupSession.create(
                        provider, providerUserId, email, name, tokenService.sha256(signupToken), 10
                );
                socialSignupSessionRepository.save(session);

                targetUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                        .path("/auth/social/complete")
                        .fragment("signupToken=" + signupToken)
                        .build().toUriString();
            }
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void processLogin(User user, SocialAccount socialAccount, HttpServletResponse response) {
        socialAccount.updateLastLoginAt();
        socialAccountRepository.save(socialAccount);

        user.updateLastLoginAt();
        userRepository.save(user);
    }

    private String buildLoginSuccessUrl(String accessToken) {
        return UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path("/home")
                .queryParam("accessToken", accessToken)
                .build().toUriString();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .path("/")
                .sameSite(refreshCookieSameSite)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String normalizeSameSite(String sameSite) {
        if (sameSite == null || sameSite.isBlank()) {
            return "Lax";
        }
        return sameSite.trim();
    }
}