package com.gamerin.backend.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.auth.entity.SocialAccount;
import com.gamerin.backend.domain.auth.repository.RefreshTokenRepository;
import com.gamerin.backend.domain.auth.repository.SocialAccountRepository;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.domain.user.entity.Theme;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.oauth2.OAuth2SuccessHandler;

@SpringBootTest
class OAuth2LoginIntegrationTest {

    @Autowired
    private OAuth2SuccessHandler handler;
    @Autowired
    private SocialAccountRepository socialAccountRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @MockitoSpyBean
    private UserRepository userRepository;
    @MockitoSpyBean
    private TokenService tokenService;

    @Test
    void withdrawalAfterEmailLookupDoesNotLeaveSocialLink() throws Exception {
        User user = saveUser();
        withdraw(user);
        // Return the snapshot read before the other transaction committed withdrawal.
        doReturn(Optional.of(user)).when(userRepository).findByEmail(user.getEmail());
        MockHttpServletResponse response = login(user);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=account_withdrawn");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", user.getId().toString())).isEmpty();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void orphanedSocialLinkIsRemovedAndNextLoginCanSignup() throws Exception {
        User user = saveUser();
        withdraw(user);
        socialAccountRepository.saveAndFlush(SocialAccount.create(
                user.getId(), "GOOGLE", user.getId().toString(), user.getEmail(), "User"));

        assertThat(login(user).getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=account_withdrawn");
        assertThat(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", user.getId().toString())).isEmpty();
        assertThat(login(user).getRedirectedUrl()).startsWith("http://localhost:3000/auth/social/complete#signupToken=");
    }

    @Test
    void tokenFailureRollsBackSocialLinkAndLoginTimestamp() {
        User user = saveUser();
        doThrow(new IllegalStateException("token failure")).when(tokenService).issueTokens(any(User.class));

        assertThatThrownBy(() -> login(user)).isInstanceOf(IllegalStateException.class).hasMessage("token failure");

        assertThat(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", user.getId().toString())).isEmpty();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoginAt()).isNull();
    }

    @Test
    void successfulLoginLinksAccountAndPreservesTheme() throws Exception {
        User user = saveUser();

        MockHttpServletResponse response = login(user);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/auth/oauth-success");
        assertThat(response.getHeader("Set-Cookie")).isNotNull();
        assertThat(socialAccountRepository.findByProviderAndProviderUserId("GOOGLE", user.getId().toString())).isPresent();
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getTheme()).isEqualTo(Theme.LIGHT);
        assertThat(saved.getLastLoginAt()).isNotNull();
        assertThat(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())).hasSize(1);
    }

    private User saveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal("oauth+" + suffix + "@example.com", "oauth" + suffix, "User", "password-hash");
        user.setProfile(UserProfile.createDefault(user));
        user.updateTheme(Theme.LIGHT);
        return userRepository.saveAndFlush(user);
    }

    private void withdraw(User user) {
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findActiveByIdForUpdate(user.getId()).orElseThrow().withdraw());
    }

    private MockHttpServletResponse login(User user) throws Exception {
        DefaultOAuth2User principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("sub", user.getId().toString(), "email", user.getEmail(), "name", "User", "email_verified", true),
                "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        return response;
    }
}
