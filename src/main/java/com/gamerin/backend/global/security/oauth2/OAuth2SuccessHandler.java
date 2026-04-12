package com.gamerin.backend.global.security.oauth2;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public OAuth2SuccessHandler(UserRepository userRepository, JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        
        String provider = oauthToken.getAuthorizedClientRegistrationId(); // "google"
        String providerId = oAuth2User.getAttribute("sub"); // 구글의 고유 식별자
        String email = oAuth2User.getAttribute("email");

        Optional<User> userOptional = userRepository.findByProviderAndProviderId(provider, providerId);

        String targetUrl;
        if (userOptional.isPresent()) {
            // 1. 이미 가입된 회원인 경우: 기존 회원용 로그인 연동 토큰(loginToken) 등을 발급해 리다이렉트 
            // (간소화를 위해 프론트엔드의 로그인 콜백 페이지로 이메일/provider 정보를 넘겨 자체 로그인 API를 호출하게 하거나, 여기서 직접 Cookie를 굽는 방식을 씁니다.)
            targetUrl = UriComponentsBuilder.fromUriString("http://localhost:3000/auth/social/callback") // 임시로 주소 지정해놓은것 프론트 측에서 수정하여 써도됨
                    .queryParam("provider", provider)
                    .queryParam("providerId", providerId)
                    .build().toUriString();
        } else {
            // 2. 신규 가입이 필요한 경우: 임시 가입 토큰 발급 후 추가 정보 입력 페이지로 이동
            String registerToken = jwtTokenProvider.createRegisterToken(email, provider, providerId);
            targetUrl = UriComponentsBuilder.fromUriString("http://localhost:3000/signup/social") // 임시로 주소 지정해놓은것 프론트 측에서 수정하여 써도됨
                    .queryParam("registerToken", registerToken)
                    .build().toUriString();
        }

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}