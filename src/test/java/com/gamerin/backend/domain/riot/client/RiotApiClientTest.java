package com.gamerin.backend.domain.riot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RiotApiClientTest {

    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private HttpServer server;
    private RiotApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                releaseResponse.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new RiotApiClient("test-api-key", baseUrl, baseUrl, Duration.ofSeconds(1), Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        server.stop(0);
    }

    @Test
    void findAccountMapsReadTimeoutTo502() {
        assertThatThrownBy(() -> client.findAccount("player", "KR1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getReason()).isEqualTo("Riot API 연동 중 오류가 발생했습니다.");
                });
    }

    @Test
    void findLeagueEntriesByPuuidMapsReadTimeoutTo502() {
        assertThatThrownBy(() -> client.findLeagueEntriesByPuuid("puuid"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getReason()).isEqualTo("Riot 리그 전적 조회 중 오류가 발생했습니다.");
                });
    }
}
