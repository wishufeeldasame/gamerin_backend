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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PubgApiClientTest {

    private final PubgApiClient pubgApiClient = new PubgApiClient("test-api-key");

    @Test
    void resolveRankedKdaUsesPositiveApiKdaWhenAvailable() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                2.5,
                null,
                null,
                null,
                null,
                null
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(2.5);
    }

    @Test
    void resolveRankedKdaUsesOfficialKdaBeforeDeprecatedKdr() {
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

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(6.1);
    }

    @Test
    void resolveRankedKdaCalculatesKillDeathAssistRatioWhenApiKdaIsMissing() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                null,
                3.8,
                null,
                49,
                30,
                13
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(79 / 13.0);
    }

    @Test
    void resolveRankedKdaUsesKillsAndAssistsWhenDeathsAreZero() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                null,
                3.8,
                null,
                49,
                30,
                0
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(79.0);
    }

    @Test
    void resolveRankedKdaPreservesOfficialZeroKda() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                0.0,
                3.8,
                null,
                49,
                30,
                13
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isZero();
    }

    @Test
    void resolveRankedKdaCalculatesFromAggregatesWhenApiKdaIsNegative() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                -1.0,
                3.8,
                null,
                49,
                30,
                13
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(79 / 13.0);
    }

    @Test
    void resolveRankedKdaReturnsZeroWhenAnyCalculationFieldIsMissing() {
        List<RankedGameModeStats> statsWithMissingField = List.of(
                rankedStats(null, null, 30, 13),
                rankedStats(null, 49, null, 13),
                rankedStats(null, 49, 30, null)
        );

        assertThat(statsWithMissingField).allSatisfy(stats -> {
            Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);
            assertThat(kda).isZero();
        });
    }

    @Test
    void resolveRankedKdaReturnsZeroWhenAnyCalculationFieldIsNegative() {
        List<RankedGameModeStats> statsWithNegativeField = List.of(
                rankedStats(-1.0, -1, 30, 13),
                rankedStats(-1.0, 49, -1, 13),
                rankedStats(-1.0, 49, 30, -1)
        );

        assertThat(statsWithNegativeField).allSatisfy(stats -> {
            Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);
            assertThat(kda).isZero();
        });
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

        PubgApiClient client = new PubgApiClient("test-api-key");
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

        PubgApiClient client = new PubgApiClient("test-api-key");
        ReflectionTestUtils.setField(client, "restClient", restClient);
        return client;
    }

    private RankedGameModeStats rankedStats(
            Double kda,
            Integer kills,
            Integer assists,
            Integer deaths
    ) {
        return new RankedGameModeStats(
                16, 5, kda, 3.8, null, kills, assists, deaths
        );
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
    void resolveNormalKdaUsesPositiveApiKdaWhenAvailable() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                1.25,
                null,
                null,
                null
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKda", stats);

        assertThat(kda).isEqualTo(1.25);
    }

    @Test
    void resolveNormalKdaCalculatesKillDeathRatioWhenApiKdaIsZero() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                6,
                9
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKda", stats);

        assertThat(kda).isEqualTo(3.0);
    }

    @Test
    void resolveNormalKdaFallsBackToLossesWhenDeathsAreMissing() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                18,
                null,
                9
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKda", stats);

        assertThat(kda).isEqualTo(2.0);
    }

    @Test
    void resolveNormalKdaPreservesZeroWhenNoKillDeathOrLossFieldsExist() {
        NormalGameModeStats stats = new NormalGameModeStats(
                10,
                2,
                0.0,
                null,
                null,
                null
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveNormalKda", stats);

        assertThat(kda).isZero();
    }
}
