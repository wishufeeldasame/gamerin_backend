package com.gamerin.backend.global.security.oauth2;

import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.repository.SocialSignupSessionRepository;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2SuccessHandlerTest {

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private SocialSignupSessionRepository socialSignupSessionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    private OAuth2SuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2SuccessHandler(
                socialAccountRepository, socialSignupSessionRepository, userRepository, tokenService,
                "http://localhost:3000", "refresh_token", false, "Lax");
    }

    @Test
    void unverifiedGoogleEmailIsRejectedWithoutSignupOrLinking() throws Exception {
        when(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub"))
                .thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication(false));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=email_not_verified");
        verifyNoInteractions(userRepository, socialSignupSessionRepository);
        verify(socialAccountRepository, never()).save(any());
    }

    @Test
    void verifiedGoogleEmailOpensSignupSession() throws Exception {
        when(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", "google-sub"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(tokenService.generateOpaqueToken()).thenReturn("signup-token");
        when(tokenService.sha256(anyString())).thenReturn("hashed-signup-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication(true));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:3000/auth/social/complete#signupToken=signup-token");
        verify(socialSignupSessionRepository).save(any(SocialSignupSession.class));
    }

    private OAuth2AuthenticationToken authentication(boolean emailVerified) {
        DefaultOAuth2User user = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", "google-sub", "email", "user@example.com", "name", "User", "email_verified", emailVerified),
                "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }
}
