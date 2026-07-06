package com.gamerin.backend.global.security.jwt;

import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String MESSAGE_STREAM_PATH = "/api/v1/messages/stream";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final SseStreamTokenService sseStreamTokenService;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            CustomUserDetailsService customUserDetailsService,
            SseStreamTokenService sseStreamTokenService
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.customUserDetailsService = customUserDetailsService;
        this.sseStreamTokenService = sseStreamTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            String token = bearerToken.substring(7);
            if (jwtTokenProvider.validate(token)) {
                authenticate(request, jwtTokenProvider.getUserId(token));
            }
        } else if (MESSAGE_STREAM_PATH.equals(request.getRequestURI())) {
            sseStreamTokenService.resolve(request)
                    .ifPresent(userId -> authenticate(request, userId));
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, UUID userId) {
        try {
            CustomUserPrincipal principal = customUserDetailsService.loadById(userId);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UsernameNotFoundException ignored) {
            // DB에서 더 이상 유효하지 않은 사용자면 익명 상태로 처리한다.
            SecurityContextHolder.clearContext();
        }
    }
}
