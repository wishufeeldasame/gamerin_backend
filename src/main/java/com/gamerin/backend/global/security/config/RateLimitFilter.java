package com.gamerin.backend.global.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimitFilter extends OncePerRequestFilter {

    // 제한 대상 공개 인증 API (정확히 일치하는 경로만 제한해 임의 접미사로 키가 늘지 않게 한다)
    private static final Set<String> RATE_LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/social-signup",
            "/api/v1/auth/find-id",
            "/api/v1/auth/find-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/password",
            "/api/v1/auth/refresh",
            "/api/v1/auth/availability/handle",
            "/api/v1/auth/email-verifications",
            "/api/v1/auth/email-verifications/confirm"
    );

    // 요청 제한 기간(초) 및 허용 횟수
    private static final int LIMIT_PERIOD_SECONDS = 60;
    private static final int MAX_REQUESTS_PER_PERIOD = 10; // 60초당 최대 10회 허용

    // 추적하는 키(경로:IP) 수 상한과 만료 키 정리 주기
    static final int MAX_TRACKED_KEYS = 10_000;
    private static final long EVICTION_INTERVAL_MILLIS = 1_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    // IP + API 경로별 요청 시간 기록 (Sliding Window)
    private final Map<String, List<Instant>> requestLogs = new ConcurrentHashMap<>();
    private final AtomicLong lastEvictionMillis = new AtomicLong();

    public RateLimitFilter(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    RateLimitFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // MVC 라우팅과 같은 기준(디코딩, ';' 제거)으로 비교해 인코딩된 경로로 제한을 피하지 못하게 한다.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);

        if (RATE_LIMITED_PATHS.contains(path)) {
            Instant now = clock.instant();
            Instant windowStart = now.minusSeconds(LIMIT_PERIOD_SECONDS);
            evictExpiredKeys(now, windowStart);

            // 클라이언트가 보낸 X-Forwarded-For는 믿지 않는다. 운영(prod)은 forward-headers-strategy: framework가
            // 신뢰 프록시(nginx)가 덮어쓴 값을 remoteAddr에 반영하고, 그 외 프로필은 실제 접속 주소를 쓴다.
            String key = path + ":" + request.getRemoteAddr();

            // 상한에 도달하면 새 키는 추적하지 않고 차단한다 (메모리 보호, 인증 API라 fail-closed).
            if (requestLogs.size() >= MAX_TRACKED_KEYS && !requestLogs.containsKey(key)) {
                sendErrorResponse(response);
                return;
            }

            boolean[] allowed = new boolean[1];
            requestLogs.compute(key, (k, timestamps) -> {
                List<Instant> window = timestamps != null ? timestamps : new ArrayList<>();
                // 현재 시간 기준 60초 이전의 기록 삭제
                window.removeIf(timestamp -> timestamp.isBefore(windowStart));
                allowed[0] = window.size() < MAX_REQUESTS_PER_PERIOD;
                if (allowed[0]) {
                    window.add(now);
                }
                return window;
            });

            // 최대 허용 횟수 초과 시 차단
            if (!allowed[0]) {
                sendErrorResponse(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    // 창이 지난 기록만 남은 키를 지운다. 한 번에 한 스레드만, 최대 초당 1회 수행한다.
    private void evictExpiredKeys(Instant now, Instant windowStart) {
        long last = lastEvictionMillis.get();
        long nowMillis = now.toEpochMilli();
        if (nowMillis - last < EVICTION_INTERVAL_MILLIS || !lastEvictionMillis.compareAndSet(last, nowMillis)) {
            return;
        }
        for (String key : requestLogs.keySet()) {
            requestLogs.computeIfPresent(key, (k, timestamps) -> {
                timestamps.removeIf(timestamp -> timestamp.isBefore(windowStart));
                return timestamps.isEmpty() ? null : timestamps;
            });
        }
    }

    int trackedKeyCount() {
        return requestLogs.size();
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
