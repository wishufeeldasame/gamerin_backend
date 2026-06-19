package com.gamerin.backend.global.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    // IP + API 경로별 요청 시간 기록 (Sliding Window)
    private final Map<String, List<Instant>> requestLogs = new ConcurrentHashMap<>();

    // 요청 제한 기간(초) 및 허용 횟수
    private static final int LIMIT_PERIOD_SECONDS = 60;
    private static final int MAX_REQUESTS_PER_PERIOD = 10; // 60초당 최대 10회 허용

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. 보안 대상 공개 인증 API 패턴 검사
        if (isRateLimitedPath(path)) {
            String clientIp = getClientIp(request);
            String key = path + ":" + clientIp;
            Instant now = Instant.now();

            List<Instant> timestamps = requestLogs.computeIfAbsent(key, k -> new ArrayList<>());
            synchronized (timestamps) {
                // 현재 시간 기준 60초 이전의 기록 삭제
                Instant windowStart = now.minusSeconds(LIMIT_PERIOD_SECONDS);
                timestamps.removeIf(timestamp -> timestamp.isBefore(windowStart));

                // 최대 허용 횟수 초과 시 차단
                if (timestamps.size() >= MAX_REQUESTS_PER_PERIOD) {
                    sendErrorResponse(response);
                    return;
                }
                timestamps.add(now);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isRateLimitedPath(String path) {
        return path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/signup")
                || path.startsWith("/api/v1/auth/social-signup")
                || path.startsWith("/api/v1/auth/find-password")
                || path.startsWith("/api/v1/auth/reset-password")
                || path.startsWith("/api/v1/auth/refresh")
                || path.startsWith("/api/v1/auth/availability");
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void sendErrorResponse(HttpServletResponse response) throws IOException {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // HTTP 429 Too Many Requests
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
    
            Map<String, Object> errorBody = Map.of(
                    "success", false,
                    "message", "요청 횟수가 너무 많습니다. 잠시 후 다시 시도해 주세요 (최대 분당 " + MAX_REQUESTS_PER_PERIOD + "회)."
            );
    
            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
        }
}