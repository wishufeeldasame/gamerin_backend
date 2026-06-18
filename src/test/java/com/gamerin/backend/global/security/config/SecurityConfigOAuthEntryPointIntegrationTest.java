package com.gamerin.backend.global.security.config;

import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigOAuthEntryPointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void protectedHtmlRequestDoesNotStartOAuthLoginAutomatically() throws Exception {
        mockMvc.perform(get("/protected-browser-page").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void protectedApiRequestReturnsJsonUnauthorizedWithoutOAuthRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("인증이 필요하거나 토큰이 만료되었습니다."));
    }

    @Test
    void directMessageAttachmentStaticPathRequiresAuthenticationWithoutOAuthRedirect() throws Exception {
        mockMvc.perform(get("/uploads/message-attachments/private.jpg").accept("image/jpeg"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void directMessageAttachmentStaticPathIsForbiddenForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/uploads/message-attachments/private.jpg")
                        .accept("image/jpeg")
                        .with(user("tester")))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void directMessageAttachmentStaticPathIsForbiddenForBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal principal = principal(userId, "tester", "Tester");
        String accessToken = jwtTokenProvider.createAccessToken(userId, "tester", List.of("USER"));

        when(customUserDetailsService.loadById(userId)).thenReturn(principal);

        mockMvc.perform(get("/uploads/message-attachments/private.jpg")
                        .accept("image/jpeg")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void googleAuthorizationEndpointStillStartsOAuthLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }

    private CustomUserPrincipal principal(UUID userId, String handle, String nickname) {
        User user = User.createLocal("user@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        return CustomUserPrincipal.from(user);
    }
}
