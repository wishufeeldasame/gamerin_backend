package com.gamerin.backend.domain.message.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

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
}
