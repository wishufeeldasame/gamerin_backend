package com.gamerin.backend.domain.r6.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class R6DataStatsClientTest {

    private HttpServer server;
    private Function<Map<String, String>, StubResponse> responder;
    private final List<RecordedRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/stats", this::handleRequest);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void findProfileReturns503WhenRequiredConfigurationIsMissing() {
        R6DataStatsClient client = new R6DataStatsClient(
                "",
                "",
                Duration.ofSeconds(3),
                Duration.ofSeconds(10)
        );

        assertThatThrownBy(() -> client.findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(requests).isEmpty();
    }

    @Test
    void findProfileUsesR6DataHttpContractAndFetchesSeasonalTierWhenNeeded() {
        responder = query -> switch (query.get("type")) {
            case "fullStats" -> StubResponse.ok(fixture("full-stats-ranked.json"));
            case "seasonalStats" -> StubResponse.ok(fixture("seasonal-stats.json"));
            default -> new StubResponse(400, "{}");
        };

        R6Profile profile = client().findProfile("Player + One");

        assertThat(profile.playerName()).isEqualTo("CanonicalPlayer");
        assertThat(profile.accountId()).isEqualTo("account-41");
        assertThat(profile.summary().tierLabel()).isEqualTo("EMERALD II");
        assertThat(profile.summary().statsMode()).isEqualTo(GameStatsMode.RANKED);

        assertThat(requests).hasSize(2);
        RecordedRequest fullStatsRequest = requests.get(0);
        assertThat(fullStatsRequest.apiKey()).isEqualTo("test-api-key");
        assertThat(fullStatsRequest.query())
                .containsEntry("type", "fullStats")
                .containsEntry("nameOnPlatform", "Player + One")
                .containsEntry("platformType", "uplay")
                .containsEntry("modes", "all");

        RecordedRequest seasonalStatsRequest = requests.get(1);
        assertThat(seasonalStatsRequest.apiKey()).isEqualTo("test-api-key");
        assertThat(seasonalStatsRequest.query())
                .containsEntry("type", "seasonalStats")
                .containsEntry("nameOnPlatform", "Player + One")
                .containsEntry("platformType", "uplay")
                .doesNotContainKey("modes");
    }

    @Test
    void findProfileDoesNotFetchSeasonalStatsWhenRankedSnapshotIsAbsent() {
        responder = query -> StubResponse.ok(fixture("full-stats-unranked.json"));

        R6Profile profile = client().findProfile("UnrankedPlayer");

        assertThat(profile.accountId()).isEqualTo("unranked-account");
        assertThat(profile.summary().tierLabel()).isNull();
        assertThat(profile.summary().statsMode()).isNull();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).query()).containsEntry("type", "fullStats");
    }

    @Test
    void findProfileDoesNotFetchSeasonalStatsForNormalFallback() {
        responder = query -> StubResponse.ok(fixture("full-stats-normal-fallback.json"));

        R6Profile profile = client().findProfile("NormalPlayer");

        assertThat(profile.summary().tierLabel()).isNull();
        assertThat(profile.summary().matches()).isEqualTo(50);
        assertThat(profile.summary().statsMode()).isEqualTo(GameStatsMode.NORMAL);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).query())
                .containsEntry("type", "fullStats")
                .containsEntry("modes", "all");
    }

    @Test
    void getSummaryReturns502ForMissingProfileReference() {
        assertThatThrownBy(() -> client().getSummary(new R6ProfileRef(" ", "account-1")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(requests).isEmpty();
    }

    @Test
    void getSummaryRejectsPlayerNameReassignedToDifferentAccount() {
        responder = query -> StubResponse.ok(fixture("full-stats-normal-fallback.json"));

        assertThatThrownBy(() -> client().getSummary(new R6ProfileRef("NormalPlayer", "original-account")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());

        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "401, 503",
            "403, 503",
            "404, 404",
            "429, 429",
            "500, 502",
            "502, 502"
    })
    void findProfileMapsExternalHttpErrors(int externalStatus, int expectedStatus) {
        responder = query -> new StubResponse(externalStatus, "{\"error\":\"external failure\"}");

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(expectedStatus);
    }

    @Test
    void findProfileMapsReadTimeoutRestClientExceptionTo502() {
        CountDownLatch releaseResponse = new CountDownLatch(1);
        responder = query -> StubResponse.blocked(fixture("full-stats-unranked.json"), releaseResponse);

        try {
            assertThatThrownBy(() -> client(Duration.ofSeconds(1), Duration.ofMillis(50))
                    .findProfile("R6Player"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(HttpStatus.BAD_GATEWAY.value());
        } finally {
            releaseResponse.countDown();
        }

        assertThat(requests).hasSize(1);
    }

    @Test
    void findProfileReturns502ForMissingAccountIdentifier() {
        responder = query -> StubResponse.ok("""
                {
                  "data": {
                    "platformInfo": {
                      "platformUserHandle": "R6Player"
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
    }

    @Test
    void findProfileMapsMalformedStatsTo502() {
        responder = query -> StubResponse.ok(fixture("full-stats-ranked.json").replace(
                "\"kdRatio\": { \"value\": 1.05 }",
                "\"kdRatio\": { \"value\": \"invalid\" }"
        ));

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());

        assertThat(requests).hasSize(1);
    }

    private R6DataStatsClient client() {
        return client(Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    private R6DataStatsClient client(Duration connectTimeout, Duration readTimeout) {
        return new R6DataStatsClient(
                "test-api-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                connectTimeout,
                readTimeout
        );
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String apiKey = exchange.getRequestHeaders().getFirst("api-key");
        requests.add(new RecordedRequest(query, apiKey));

        StubResponse response = responder == null
                ? new StubResponse(500, "{}")
                : responder.apply(query);
        if (response.releaseResponse() != null) {
            try {
                response.releaseResponse().await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            query.put(key, value);
        }
        return query;
    }

    private String fixture(String name) {
        String path = "/fixtures/r6/" + name;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(path), path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record RecordedRequest(Map<String, String> query, String apiKey) {
    }

    private record StubResponse(int status, String body, CountDownLatch releaseResponse) {

        private StubResponse(int status, String body) {
            this(status, body, null);
        }

        private static StubResponse ok(String body) {
            return new StubResponse(200, body);
        }

        private static StubResponse blocked(String body, CountDownLatch releaseResponse) {
            return new StubResponse(200, body, releaseResponse);
        }
    }
}
