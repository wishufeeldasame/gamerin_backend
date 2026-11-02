package com.gamerin.backend.domain.message.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRealtimeServiceTest {

    @Test
    void replacesOldestConnectionWhenUserExceedsLimit() {
        MessageRealtimeService service = new MessageRealtimeService(2);
        UUID userId = UUID.randomUUID();

        SseEmitter first = service.subscribe(userId);
        SseEmitter second = service.subscribe(userId);
        SseEmitter third = service.subscribe(userId);

        assertThat(service.connectionCount(userId)).isEqualTo(2);
        assertThat(service.isConnected(userId, first)).isFalse();
        assertThat(service.isConnected(userId, second)).isTrue();
        assertThat(service.isConnected(userId, third)).isTrue();
    }

    @Test
    void limitIsAppliedPerUser() {
        MessageRealtimeService service = new MessageRealtimeService(1);
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        SseEmitter connectionA = service.subscribe(userA);
        SseEmitter connectionB = service.subscribe(userB);

        assertThat(service.isConnected(userA, connectionA)).isTrue();
        assertThat(service.isConnected(userB, connectionB)).isTrue();
    }

    @Test
    void treatsNonPositiveLimitAsOne() {
        MessageRealtimeService service = new MessageRealtimeService(0);
        UUID userId = UUID.randomUUID();

        service.subscribe(userId);
        SseEmitter latest = service.subscribe(userId);

        assertThat(service.connectionCount(userId)).isEqualTo(1);
        assertThat(service.isConnected(userId, latest)).isTrue();
    }

    @Test
    void disconnectRemovesAllConnectionsOfUser() {
        MessageRealtimeService service = new MessageRealtimeService(5);
        UUID userId = UUID.randomUUID();
        service.subscribe(userId);
        service.subscribe(userId);

        service.disconnect(userId);

        assertThat(service.connectionCount(userId)).isZero();
    }

    // 동시 연결로 새 emitter가 전송 전에 밀려나 complete되어도 subscribe가 예외를 던지면 안 된다.
    @Test
    void subscribeDoesNotThrowWhenNewEmitterIsReplacedBeforeConnectedEvent() throws Exception {
        MessageRealtimeService service = new MessageRealtimeService(1);
        UUID userId = UUID.randomUUID();
        int threads = 8;
        int iterations = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<Void> task = () -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        service.subscribe(userId);
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
