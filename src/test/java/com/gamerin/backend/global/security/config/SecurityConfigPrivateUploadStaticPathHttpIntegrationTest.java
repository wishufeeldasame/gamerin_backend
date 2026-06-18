package com.gamerin.backend.global.security.config;

import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigPrivateUploadStaticPathHttpIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void directMessageAttachmentStaticPathRequiresAuthenticationOverHttp() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/uploads/message-attachments/private.jpg",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void directMessageAttachmentStaticPathIsForbiddenForBearerTokenOverHttp() {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal principal = principal(userId, "tester", "Tester");
        String accessToken = jwtTokenProvider.createAccessToken(userId, "tester", List.of("USER"));
        when(customUserDetailsService.loadById(userId)).thenReturn(principal);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/uploads/message-attachments/private.jpg",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private CustomUserPrincipal principal(UUID userId, String handle, String nickname) {
        User user = User.createLocal("user@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        return CustomUserPrincipal.from(user);
    }
}
