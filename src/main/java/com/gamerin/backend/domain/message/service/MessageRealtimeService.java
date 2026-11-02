package com.gamerin.backend.domain.message.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gamerin.backend.domain.message.dto.response.MessageRealtimeEvent;

@Service
public class MessageRealtimeService {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    static final String CONNECTION_REPLACED_EVENT = "connection-replaced";

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();
    private final int maxConnectionsPerUser;

    public MessageRealtimeService(
            @Value("${app.message.sse.max-connections-per-user:5}") int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = Math.max(1, maxConnectionsPerUser);
    }

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        List<SseEmitter> replaced = new ArrayList<>();
        // 추가와 상한 초과분 제거를 사용자 단위로 원자적으로 처리한다. 초과 시 가장 오래된 연결부터 닫는다.
        emittersByUserId.compute(userId, (ignored, current) -> {
            CopyOnWriteArrayList<SseEmitter> emitters = current != null ? current : new CopyOnWriteArrayList<>();
            emitters.add(emitter);
            while (emitters.size() > maxConnectionsPerUser) {
                replaced.add(emitters.remove(0));
            }
            return emitters;
        });

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(ignored -> removeEmitter(userId, emitter));

        replaced.forEach(this::closeReplaced);

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            removeEmitter(userId, emitter);
        }

        return emitter;
    }

    public void publish(UUID userId, MessageRealtimeEvent event) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            } catch (IOException | IllegalStateException e) {
                removeEmitter(userId, emitter);
            }
        }
    }

    // 탈퇴 등으로 더 이상 인증되지 않는 사용자의 열린 스트림을 닫는다
    public void disconnect(UUID userId) {
        List<SseEmitter> emitters = emittersByUserId.remove(userId);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
    }

    // 새 연결에 밀려난 연결은 사유 이벤트를 보낸 뒤 닫는다. 이미 끊긴 연결이면 무시한다.
    private void closeReplaced(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(CONNECTION_REPLACED_EVENT).data("replaced"));
        } catch (IOException | IllegalStateException ignored) {
            // 전송 실패와 관계없이 아래에서 닫는다.
        }
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 이미 완료된 연결이다.
        }
    }

    // 목록 갱신과 빈 목록 제거를 원자적으로 처리해 동시에 추가된 연결이 맵에서 빠지지 않게 한다.
    private void removeEmitter(UUID userId, SseEmitter emitter) {
        emittersByUserId.computeIfPresent(userId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    int connectionCount(UUID userId) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        return emitters == null ? 0 : emitters.size();
    }

    boolean isConnected(UUID userId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        return emitters != null && emitters.contains(emitter);
    }
}
