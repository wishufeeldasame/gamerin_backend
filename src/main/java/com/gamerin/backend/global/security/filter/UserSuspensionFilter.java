package com.gamerin.backend.global.security.filter;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.global.logging.JsonLogContext;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 정지(SUSPENDED) 유저의 인증 API 호출을 실시간 감지하여 403 Forbidden을 반환하는 보안 필터
 * (JwtAuthenticationFilter에서 로드된 Principal 상태를 재사용하여 불필요한 중복 DB 조회를 제거)
 */
@Component
public class UserSuspensionFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    // UserRepository 의존성 제거 (중복 SELECT 쿼리 제거로 성능 향상)
    public UserSuspensionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (isAuthenticated(authentication) && authentication.getPrincipal() instanceof CustomUserPrincipal principal) {
            // 직전 JwtAuthenticationFilter에서 조회한 실시간 계정 상태 재사용
            if (principal.isSuspended()) {
                SecurityContextHolder.clearContext();

                String message = "이용이 정지된 계정입니다. 고객센터에 문의해주세요.";
                JsonLogContext.setFailureReason(request, message);

                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");

                Map<String, Object> errorResponse = Map.of(
                        "success", false,
                        "message", message
                );

                response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
                return; // 필터 체인 중단
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}