package com.gamerin.backend.global.security.jwt;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
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

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    @Mock
    private SseStreamTokenService sseStreamTokenService;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtTokenProvider,
                customUserDetailsService,
                sseStreamTokenService);
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

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
    }

    @Test
    void authenticatesMessageStreamWithSseCookieToken() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal principal = principal(userId, "tester", "Tester");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages/stream");

        when(sseStreamTokenService.resolve(request)).thenReturn(Optional.of(userId));
        when(customUserDetailsService.loadById(userId)).thenReturn(principal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void ignoresMessageStreamAccessTokenQueryParameter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages/stream");
        request.setParameter("accessToken", "query-token");

        when(sseStreamTokenService.resolve(request)).thenReturn(Optional.empty());

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtTokenProvider, never()).validate("query-token");
    }

    @Test
    void treatsMissingUserAsUnauthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer stale-token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("stale-user", null));

        when(jwtTokenProvider.validate("stale-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("stale-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId))
                .thenThrow(new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesSuspendedUserToDownstreamSuspensionFilter() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal suspendedPrincipal = principalWithStatus(
                userId, "suspended_user", "Suspended", UserStatus.SUSPENDED, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        when(jwtTokenProvider.validate("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId)).thenReturn(suspendedPrincipal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // 다음 필터인 UserSuspensionFilter가 403 차단할 수 있도록 Authentication이 정상 전달되어야 함
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(suspendedPrincipal);
    }

    @Test
    void rejectsAuthenticationWhenUserIsDeleted() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal deletedPrincipal = principalWithStatus(
                userId, "deleted_user", "Deleted", UserStatus.DELETED, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        when(jwtTokenProvider.validate("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId)).thenReturn(deletedPrincipal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsAuthenticationWhenUserIsWithdrawn() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal withdrawnPrincipal = principalWithStatus(
                userId, "withdrawn_user", "Withdrawn", UserStatus.ACTIVE, OffsetDateTime.now());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");

        when(jwtTokenProvider.validate("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("valid-token")).thenReturn(userId);
        when(customUserDetailsService.loadById(userId)).thenReturn(withdrawnPrincipal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsSseStreamAuthenticationWhenUserIsDeleted() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomUserPrincipal deletedPrincipal = principalWithStatus(
                userId, "deleted_user", "Deleted", UserStatus.DELETED, null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages/stream");

        when(sseStreamTokenService.resolve(request)).thenReturn(Optional.of(userId));
        when(customUserDetailsService.loadById(userId)).thenReturn(deletedPrincipal);

        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private CustomUserPrincipal principal(UUID userId, String handle, String nickname) {
        User user = User.createLocal("user@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        return CustomUserPrincipal.from(user);
    }

    private CustomUserPrincipal principalWithStatus(UUID userId, String handle, String nickname, UserStatus status,
            OffsetDateTime deletedAt) {
        User user = User.createLocal("user@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "deletedAt", deletedAt);
        return CustomUserPrincipal.from(user);
    }
}