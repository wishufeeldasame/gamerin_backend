package com.gamerin.backend.global.logging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiRequestLoggingFilter extends OncePerRequestFilter {

    private static final String EVENT = "api.request";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/") || path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        Throwable thrown = null;

        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            thrown = ex;
            throw ex;
        } finally {
            if (!isAsyncStarted(request)) {
                logRequest(request, response, startedAt, thrown);
            }
        }
    }

    private void logRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            long startedAt,
            Throwable thrown
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        String path = request.getRequestURI();
        details.put("method", request.getMethod());
        details.put("path", path);
        details.put("feature", resolveFeature(path));
        details.put("operation", request.getMethod() + " " + path);
        details.put("statusCode", response.getStatus());
        details.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        details.put("clientIp", resolveClientIp(request));

        String userId = resolveAuthenticatedUserId();
        if (userId != null) {
            details.put("userId", userId);
        }

        if (thrown != null || response.getStatus() >= 400) {
            String reason = resolveFailureReason(request, response, thrown);
            JsonConsoleLogger.failure(EVENT, reason, details);
            return;
        }

        JsonConsoleLogger.success(EVENT, details);
    }

    private String resolveFailureReason(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable thrown
    ) {
        if (thrown != null) {
            Throwable cause = thrown instanceof ServletException servletException && servletException.getRootCause() != null
                    ? servletException.getRootCause()
                    : thrown;
            if (cause instanceof ResponseStatusException responseStatusException
                    && responseStatusException.getReason() != null) {
                return responseStatusException.getReason();
            }
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                return cause.getMessage();
            }
            return cause.getClass().getSimpleName();
        }

        String reason = JsonLogContext.getFailureReason(request);
        return reason != null ? reason : "HTTP status " + response.getStatus();
    }

    private String resolveAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserPrincipal customUserPrincipal) {
            return customUserPrincipal.getUserId().toString();
        }
        return null;
    }

    private String resolveFeature(String path) {
        if (path.startsWith("/api/v1/auth")) {
            return "auth";
        }
        if (path.startsWith("/api/v1/posts")) {
            return "post";
        }
        if (path.startsWith("/api/v1/feed")) {
            return "feed";
        }
        if (path.startsWith("/api/v1/pubg")) {
            return "pubg";
        }
        if (path.startsWith("/api/v1/users") && path.endsWith("/follow")) {
            return "follow";
        }
        if (path.startsWith("/api/v1/users")) {
            return "user";
        }
        if (path.startsWith("/oauth2") || path.startsWith("/login/oauth2")) {
            return "oauth2";
        }
        return "api";
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
