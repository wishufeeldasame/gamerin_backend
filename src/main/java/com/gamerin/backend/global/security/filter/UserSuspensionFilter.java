package com.gamerin.backend.global.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
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

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 정지(SUSPENDED) 유저의 인증 API 호출을 실시간 감지하여 403 Forbidden을 반환하는 보안 필터
 */
@Component
public class UserSuspensionFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public UserSuspensionFilter(UserRepository userRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (isAuthenticated(authentication) && authentication.getPrincipal() instanceof CustomUserPrincipal principal) {
            UUID userId = principal.getUserId();

            // DB 기준 실시간 계정 상태 조회 (토큰 유효기간이 남아있더라도 즉각 차단)
            User user = userRepository.findById(userId).orElse(null);

            if (user != null && user.getStatus() == UserStatus.SUSPENDED) {
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