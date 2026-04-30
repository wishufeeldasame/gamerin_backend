package com.gamerin.backend.global.security.jwt;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesRequestWhenTokenAndUserAreValid() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal principal = principal(userId, "tester", "Tester");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        when(jwtTokenProvider.validate("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId)).thenReturn(principal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
    }

    @Test
    void treatsMissingUserAsUnauthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer stale-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("stale-user", null)
        );

        when(jwtTokenProvider.validate("stale-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("stale-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId))
                .thenThrow(new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private CustomUserPrincipal principal(UUID userId, String handle, String nickname) {
        User user = User.createLocal("user@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        return CustomUserPrincipal.from(user);
    }
}
