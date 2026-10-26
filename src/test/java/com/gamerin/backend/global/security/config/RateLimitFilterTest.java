package com.gamerin.backend.global.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T00:00:00Z"));
    private final RateLimitFilter filter = new RateLimitFilter(new ObjectMapper(), clock);

    @Test
    void changingXForwardedForDoesNotBypassLimitForSameRemoteAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("/api/v1/auth/login", "203.0.113.10", "198.51.100." + i)).isEqualTo(200);
        }

        assertThat(send("/api/v1/auth/login", "203.0.113.10", "198.51.100.99")).isEqualTo(429);
        assertThat(send("/api/v1/auth/login", "203.0.113.11", null)).isEqualTo(200);
    }

    @Test
    void findIdIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("/api/v1/auth/find-id", "203.0.113.10", null)).isEqualTo(200);
        }

        assertThat(send("/api/v1/auth/find-id", "203.0.113.10", null)).isEqualTo(429);
    }

    @Test
    void existingLimitedPathsAreStillLimited() throws Exception {
        String[] paths = {
                "/api/v1/auth/login",
                "/api/v1/auth/signup",
                "/api/v1/auth/social-signup",
                "/api/v1/auth/find-password",
                "/api/v1/auth/reset-password",
                "/api/v1/auth/password",
                "/api/v1/auth/refresh",
                "/api/v1/auth/availability/handle",
                "/api/v1/auth/email-verifications",
                "/api/v1/auth/email-verifications/confirm"
        };
        for (String path : paths) {
            for (int i = 0; i < 10; i++) {
                send(path, "203.0.113.10", null);
            }
            assertThat(send(path, "203.0.113.10", null)).as(path).isEqualTo(429);
        }
    }

    @Test
    void encodedPathIsMatchedLikeMvcRouting() throws Exception {
        for (int i = 0; i < 10; i++) {
            send("/api/v1/auth/login", "203.0.113.10", null);
        }

        assertThat(send("/api/v1/auth/%6Cogin", "203.0.113.10", null)).isEqualTo(429);
    }

    @Test
    void arbitrarySuffixPathsAreNotTracked() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(send("/api/v1/auth/login/" + i, "203.0.113.10", null)).isEqualTo(200);
        }

        assertThat(filter.trackedKeyCount()).isZero();
    }

    @Test
    void expiredKeysAreEvicted() throws Exception {
        for (int i = 0; i < 50; i++) {
            send("/api/v1/auth/login", "203.0.113." + i, null);
        }
        assertThat(filter.trackedKeyCount()).isEqualTo(50);

        clock.advance(Duration.ofSeconds(61));
        send("/api/v1/auth/login", "198.51.100.1", null);

        assertThat(filter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void trackedKeysAreCappedAndNewKeysAreRejectedWhenFull() throws Exception {
        for (int i = 0; i < RateLimitFilter.MAX_TRACKED_KEYS; i++) {
            send("/api/v1/auth/login", "10.0." + (i / 256) + "." + (i % 256), null);
        }
        assertThat(filter.trackedKeyCount()).isEqualTo(RateLimitFilter.MAX_TRACKED_KEYS);

        assertThat(send("/api/v1/auth/login", "198.51.100.1", null)).isEqualTo(429);
        assertThat(send("/api/v1/auth/login", "10.0.0.0", null)).isEqualTo(200);
        assertThat(filter.trackedKeyCount()).isEqualTo(RateLimitFilter.MAX_TRACKED_KEYS);

        clock.advance(Duration.ofSeconds(61));
        assertThat(send("/api/v1/auth/login", "198.51.100.1", null)).isEqualTo(200);
        assertThat(filter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void withFrameworkForwardedHeadersLimitIsKeyedByProxyProvidedAddress() throws Exception {
        // prod(forward-headers-strategy: framework) + nginx가 X-Forwarded-For를 실제 클라이언트 IP 단일 값으로 덮어쓰는 경우
        for (int i = 0; i < 10; i++) {
            assertThat(sendThroughForwardedHeaderFilter("198.51.100.7")).isEqualTo(200);
        }

        assertThat(sendThroughForwardedHeaderFilter("198.51.100.7")).isEqualTo(429);
        assertThat(sendThroughForwardedHeaderFilter("198.51.100.8")).isEqualTo(200);
    }

    private int send(String uri, String remoteAddr, String xForwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddr);
        if (xForwardedFor != null) {
            request.addHeader("X-Forwarded-For", xForwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private int sendThroughForwardedHeaderFilter(String nginxForwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Forwarded-For", nginxForwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet okServlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
            }
        };
        new MockFilterChain(okServlet, new ForwardedHeaderFilter(), filter).doFilter(request, response);
        return response.getStatus();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
