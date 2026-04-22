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
    private final TokenService tokenService; // TokenService 주입!
    
    private final String frontendBaseUrl;
    private final String refreshCookieName;

    public OAuth2SuccessHandler(
            SocialAccountRepository socialAccountRepository,
            SocialSignupSessionRepository socialSignupSessionRepository,
            UserRepository userRepository,
            TokenService tokenService,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.auth.jwt.refresh-cookie-name:refresh_token}") String refreshCookieName
    ) {
        this.socialAccountRepository = socialAccountRepository;
        this.socialSignupSessionRepository = socialSignupSessionRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.refreshCookieName = refreshCookieName;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;

        String provider = oauthToken.getAuthorizedClientRegistrationId().toUpperCase();
        String providerUserId = oAuth2User.getAttribute("sub"); 
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");

        Optional<SocialAccount> accountOpt = socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);

        String targetUrl;
        if (accountOpt.isPresent()) {
            // [기존 유저] 토큰 공장을 통해 토큰 생성 후 로그인 처리
            SocialAccount socialAccount = accountOpt.get();
            User user = userRepository.findById(socialAccount.getUserId())
                    .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));

            socialAccount.updateLastLoginAt();
            socialAccountRepository.save(socialAccount);

            user.updateLastLoginAt();
            userRepository.save(user);

            TokenService.AuthResult result = tokenService.issueTokens(user);
            setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());

            targetUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                    .path("/home")
                    .queryParam("accessToken", result.authTokenResponse().accessToken())
                    .build().toUriString();
        } else {
            // [신규 유저] 가입 세션 생성 (토큰 공장의 암호화, 랜덤생성 로직 재사용)
            String signupToken = tokenService.generateOpaqueToken();
            SocialSignupSession session = SocialSignupSession.create(
                    provider, providerUserId, email, name, tokenService.sha256(signupToken), 10
            );
            socialSignupSessionRepository.save(session);

            // signupToken은 URL fragment로 전달해 서버 로그/Referer 헤더 노출을 방지함
            targetUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                    .path("/auth/social/complete")
                    .fragment("signupToken=" + signupToken)
                    .build().toUriString();
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(false) // 배포 시 true
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}