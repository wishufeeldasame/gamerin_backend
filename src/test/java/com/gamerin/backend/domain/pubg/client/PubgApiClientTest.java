package com.gamerin.backend.domain.pubg.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gamerin.backend.domain.pubg.dto.external.NormalGameModeStats;
import com.gamerin.backend.domain.pubg.dto.external.PubgRankedStatsResponse;
import com.gamerin.backend.domain.pubg.dto.external.PubgRankedStatsResponse.RankedAttributes;
import com.gamerin.backend.domain.pubg.dto.external.PubgRankedStatsResponse.RankedData;
import com.gamerin.backend.domain.pubg.dto.external.RankedGameModeStats;
import com.gamerin.backend.domain.pubg.exception.NoRankedRecordException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PubgApiClientTest {

    private final PubgApiClient pubgApiClient = new PubgApiClient("test-api-key", "https://api.pubg.com", Duration.ofSeconds(3), Duration.ofSeconds(10));

    @Test
    void findAccountIdMapsReadTimeoutTo502() throws IOException {
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        try {
            PubgApiClient client = new PubgApiClient(
                    "test-api-key",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(50)
            );

            assertThatThrownBy(() -> client.findAccountId("player"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(exception.getReason()).isEqualTo("Failed to fetch PUBG player information.");
                    });
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void resolveRankedKdUsesKillsAndDeathsAndIgnoresKdaKdrAndAssists() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                6.1,
                3.8,
                null,
                49,
                30,
                13
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKd", stats);

        assertThat(kd).isEqualTo(49 / 13.0);
    }

    @Test
    void resolveRankedKdReturnsNullWhenDeathsAreZero() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                6.1,
                3.8,
                null,
                49,
                30,
                0
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void resolveRankedKdReturnsNullWhenKillsOrDeathsAreUnavailable() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                6.1,
                3.8,
                null,
                null,
                30,
                13
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void resolveRankedKdReturnsNullForNegativeAggregates() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                6.1,
                3.8,
                null,
                -1,
                30,
                13
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void getRankedStatsTreatsNullResponseAsBadGateway() {
        assertRankedStatsFailure(null, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsMissingDataAsBadGateway() {
        PubgRankedStatsResponse response = new PubgRankedStatsResponse(null);

        assertRankedStatsFailure(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsMissingAttributesAsBadGateway() {
        PubgRankedStatsResponse response = new PubgRankedStatsResponse(new RankedData(null));

        assertRankedStatsFailure(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsMissingGameModeStatsMapAsBadGateway() {
        PubgRankedStatsResponse response = new PubgRankedStatsResponse(
                new RankedData(new RankedAttributes(null))
        );

        assertRankedStatsFailure(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsMissingSelectedModeAsNoRankedRecord() {
        PubgRankedStatsResponse response = new PubgRankedStatsResponse(
                new RankedData(new RankedAttributes(Map.of()))
        );

        assertNoRankedRecord(response);
    }

    @Test
    void getRankedStatsTreatsNullSelectedModeValueAsBadGateway() {
        Map<String, RankedGameModeStats> statsByMode = new HashMap<>();
        statsByMode.put("squad", null);
        PubgRankedStatsResponse response = new PubgRankedStatsResponse(
                new RankedData(new RankedAttributes(statsByMode))
        );

        assertRankedStatsFailure(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsMissingRoundsPlayedAsBadGateway() {
        RankedGameModeStats stats = new RankedGameModeStats(
                null,
                0,
                0.0,
                0.0,
                null,
                0,
                0,
                0
        );

        assertRankedStatsFailure(rankedResponse(stats), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsNegativeRoundsPlayedAsBadGateway() {
        RankedGameModeStats stats = new RankedGameModeStats(
                -1,
                0,
                0.0,
                0.0,
                null,
                0,
                0,
                0
        );

        assertRankedStatsFailure(rankedResponse(stats), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankedStatsTreatsZeroRoundsPlayedAsNoRankedRecord() {
        RankedGameModeStats stats = new RankedGameModeStats(
                0,
                0,
                0.0,
                0.0,
                null,
                0,
                0,
                0
        );

        assertNoRankedRecord(rankedResponse(stats));
    }

    @Test
    void getRankedStatsTreatsUpstreamNotFoundAsBadGateway() {
        HttpClientErrorException upstreamNotFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                new HttpHeaders(),
                new byte[0],
                StandardCharsets.UTF_8
        );
        assertThat(upstreamNotFound).isInstanceOf(HttpClientErrorException.NotFound.class);
        PubgApiClient client = clientThrowing(upstreamNotFound);

        assertThatThrownBy(() -> client.getRankedStats("account-1", "season-1", "squad"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    private void assertNoRankedRecord(PubgRankedStatsResponse response) {
        PubgApiClient client = clientReturning(response);

        assertThatThrownBy(() -> client.getRankedStats("account-1", "season-1", "squad"))
                .isExactlyInstanceOf(NoRankedRecordException.class);
    }

    private void assertRankedStatsFailure(PubgRankedStatsResponse response, HttpStatus expectedStatus) {
        PubgApiClient client = clientReturning(response);

        assertThatThrownBy(() -> client.getRankedStats("account-1", "season-1", "squad"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(expectedStatus));
    }

    private PubgApiClient clientReturning(PubgRankedStatsResponse response) {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get()
                .uri("/shards/{platform}/players/{accountId}/seasons/{seasonId}/ranked",
                        "steam", "account-1", "season-1")
                .retrieve()
                .body(PubgRankedStatsResponse.class))
                .thenReturn(response);

        PubgApiClient client = new PubgApiClient("test-api-key", "https://api.pubg.com", Duration.ofSeconds(3), Duration.ofSeconds(10));
        ReflectionTestUtils.setField(client, "restClient", restClient);
        return client;
    }

    private PubgApiClient clientThrowing(RuntimeException failure) {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get()
                .uri("/shards/{platform}/players/{accountId}/seasons/{seasonId}/ranked",
                        "steam", "account-1", "season-1")
                .retrieve()
                .body(PubgRankedStatsResponse.class))
                .thenThrow(failure);

        PubgApiClient client = new PubgApiClient("test-api-key", "https://api.pubg.com", Duration.ofSeconds(3), Duration.ofSeconds(10));
        ReflectionTestUtils.setField(client, "restClient", restClient);
        return client;
    }

    private PubgRankedStatsResponse rankedResponse(RankedGameModeStats stats) {
        return new PubgRankedStatsResponse(
                new RankedData(new RankedAttributes(Map.of("squad", stats)))
        );
    }

    @Test
    void hasRankedRecordReturnsFalseWhenNoRoundsPlayed() {
        RankedGameModeStats stats = new RankedGameModeStats(
                0,
                0,
                0.0,
                0.0,
                null,
                0,
                0,
                0
        );

        Boolean hasRankedRecord = ReflectionTestUtils.invokeMethod(pubgApiClient, "hasRankedRecord", stats);

        assertThat(hasRankedRecord).isFalse();
    }

    @Test
    void hasRankedRecordReturnsTrueWhenRoundsPlayedExists() {
        RankedGameModeStats stats = new RankedGameModeStats(
                1,
                0,
                0.0,
                0.0,
                null,
                0,
                0,
                0
        );

        Boolean hasRankedRecord = ReflectionTestUtils.invokeMethod(pubgApiClient, "hasRankedRecord", stats);

        assertThat(hasRankedRecord).isTrue();
    }

    @Test
    void resolveNormalKdUsesKillsAndDeathsAndIgnoresApiRatio() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                9.99,
                18,
                6,
                9
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isEqualTo(3.0);
    }

    @Test
    void resolveNormalKdReturnsNullWhenDeathsAreZero() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                0,
                9
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void resolveNormalKdUsesLossesWhenSeasonStatsOmitDeaths() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                null,
                9
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isEqualTo(2.0);
    }

    @Test
    void resolveNormalKdReturnsNullWhenDeathsAndLossesAreMissing() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                null,
                null
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void resolveNormalKdReturnsNullWhenFallbackLossesAreNegative() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                null,
                -1
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isNull();
    }

    @Test
    void resolveNormalKdPreservesRealZeroKills() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                0,
                4,
                null
        );

        Double kd = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKd", stats);

        assertThat(kd).isZero();
    }
}
