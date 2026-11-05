package com.gamerin.backend.domain.message.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.message.service.MessageRealtimeService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.security.jwt.SseStreamTokenService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.message.sse.max-connections-per-user=5")
class MessageStreamSseHttpIntegrationTest {

    private static final String STREAM_PATH = "/api/v1/messages/stream";
    private static final String EOF = "<EOF>";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private SseStreamTokenService sseStreamTokenService;

    @Autowired
    private MessageRealtimeService messageRealtimeService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private UserRepository userRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<SseConnection> connections = new ArrayList<>();
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        User user = User.createLocal("sse@example.com", "sseuser", "SseUser", "encoded-password");
        ReflectionTestUtils.setField(user, "id", userId);
        when(customUserDetailsService.loadById(userId)).thenReturn(CustomUserPrincipal.from(user));
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
    }

    @AfterEach
    void closeConnections() {
        connections.forEach(SseConnection::close);
        // 서버 쪽 emitter도 닫아야 컨텍스트 종료 시 graceful shutdown이 지연되지 않는다.
        messageRealtimeService.disconnect(userId);
    }

    @Test
    void replacedConnectionGetsReplacedEventThenEofAndTokenIsOneTime(CapturedOutput output) throws Exception {
        String reusedToken = sseStreamTokenService.issue(userId).token();
        SseConnection oldest = open(reusedToken);
        oldest.awaitLine("event:connected");
        for (int i = 0; i < 4; i++) {
            open(sseStreamTokenService.issue(userId).token()).awaitLine("event:connected");
        }

        // 상한(5)을 넘기는 6번째 연결이 가장 오래된 연결을 밀어낸다.
        open(sseStreamTokenService.issue(userId).token()).awaitLine("event:connected");

        oldest.awaitLine(EOF);

        // 밀려난 연결이 받은 원문: 이벤트 사유는 따옴표 없는 replaced이고, 그 뒤 스트림이 EOF로 닫힌다.
        assertThat(oldest.lines).containsExactly(
                "event:connected", "data:ok", "",
                "event:connection-replaced", "data:replaced", "",
                EOF);
        // 같은 쿠키(일회용 토큰)로 재연결하면 401이다.
        assertThat(statusOf(reusedToken)).isEqualTo(401);

        // emitter.complete() 뒤 ASYNC 재디스패치가 인가 필터에서 거부되어 서버 오류 로그를 남기면 안 된다.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll())
                .doesNotContain("Unable to handle the Spring Security Exception")
                .doesNotContain("AuthorizationDeniedException"));
    }

    @Test
    void streamWithoutCookieIsUnauthorized() throws Exception {
        assertThat(statusOf(null)).isEqualTo(401);
    }

    @Test
    void protectedApiWithoutAuthenticationIsUnauthorized() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/messages/conversations"))
                .timeout(READ_TIMEOUT)
                .GET()
                .build();
        assertThat(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private SseConnection open(String token) throws Exception {
        SseConnection connection = new SseConnection(token);
        connections.add(connection);
        return connection;
    }

    private int statusOf(String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(streamUri()).timeout(READ_TIMEOUT).GET();
        if (token != null) {
            builder.header("Cookie", cookie(token));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private URI streamUri() {
        return URI.create("http://localhost:" + port + STREAM_PATH);
    }

    private static String cookie(String token) {
        return "message_stream_token=" + token;
    }

    /** 응답 본문을 줄 단위로 읽어 원문 그대로 기록한다. 스트림 종료는 EOF 마커로 남긴다. */
    private final class SseConnection {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile InputStream body;

        SseConnection(String token) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(streamUri())
                    .header("Cookie", cookie(token))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            body = response.body();
            reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                        queue.add(line);
                    }
                } catch (IOException ignored) {
                    // close() 등으로 끊긴 경우도 EOF로 취급한다.
                }
                lines.add(EOF);
                queue.add(EOF);
            });
            reader.setDaemon(true);
            reader.start();
        }

        /** 지정한 줄이 나올 때까지 읽는다(앞선 줄은 버린다). */
        String awaitLine(String expected) throws InterruptedException {
            while (true) {
                String line = awaitNextLine();
                if (line.equals(expected) || line.equals(EOF)) {
                    return line;
                }
            }
        }

        String awaitNextLine() throws InterruptedException {
            String line = queue.poll(READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(line).as("timed out waiting for SSE line").isNotNull();
            return line;
        }

        void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // 이미 닫힌 연결이다.
            }
        }
    }
}
