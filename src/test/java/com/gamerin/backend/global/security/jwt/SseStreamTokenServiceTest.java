package com.gamerin.backend.global.security.jwt;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SseStreamTokenServiceTest {

    private static final String COOKIE_NAME = "message_stream_token";

    private final SseStreamTokenService service = new SseStreamTokenService(60, COOKIE_NAME, false, "Lax");

    @Test
    void resolvesIssuedTokenOnlyOnce() {
        UUID userId = UUID.randomUUID();
        String token = service.issue(userId).token();

        assertThat(service.resolve(requestWithToken(token))).contains(userId);
        assertThat(service.resolve(requestWithToken(token))).isEmpty();
    }

    @Test
    void newlyIssuedTokenWorksAfterPreviousTokenWasConsumed() {
        UUID userId = UUID.randomUUID();
        String first = service.issue(userId).token();
        assertThat(service.resolve(requestWithToken(first))).contains(userId);

        String second = service.issue(userId).token();

        assertThat(second).isNotEqualTo(first);
        assertThat(service.resolve(requestWithToken(second))).contains(userId);
    }

    @Test
    void rejectsUnknownOrMissingToken() {
        assertThat(service.resolve(requestWithToken("unknown"))).isEmpty();
        assertThat(service.resolve(new MockHttpServletRequest("GET", "/api/v1/messages/stream"))).isEmpty();
    }

    @Test
    void onlyOneConcurrentRequestCanUseSameToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = service.issue(userId).token();
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Optional<UUID>>> tasks = IntStream.range(0, threads)
                    .<Callable<Optional<UUID>>>mapToObj(ignored -> () -> {
                        start.await();
                        return service.resolve(requestWithToken(token));
                    })
                    .toList();
            List<Future<Optional<UUID>>> futures = tasks.stream().map(executor::submit).toList();
            start.countDown();

            long resolved = 0;
            for (Future<Optional<UUID>> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).isPresent()) {
                    resolved++;
                }
            }
            assertThat(resolved).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages/stream");
        request.setCookies(new Cookie(COOKIE_NAME, token));
        return request;
    }
}
