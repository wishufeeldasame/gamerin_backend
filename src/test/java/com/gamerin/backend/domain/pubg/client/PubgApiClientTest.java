package com.gamerin.backend.domain.pubg.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.gamerin.backend.domain.pubg.dto.external.NormalGameModeStats;
import com.gamerin.backend.domain.pubg.dto.external.RankedGameModeStats;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    void resolveRankedKdaUsesPositiveApiKdrBeforeKda() {
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

        assertThat(kda).isEqualTo(3.8);
    }

    @Test
    void resolveRankedKdaCalculatesKillDeathRatioWhenApiRatiosAreZero() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                0.0,
                0.0,
                null,
                49,
                30,
                13
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isEqualTo(49 / 13.0);
    }

    @Test
    void resolveRankedKdaPreservesZeroWhenNoKillDeathFieldsExist() {
        RankedGameModeStats stats = new RankedGameModeStats(
                16,
                5,
                0.0,
                0.0,
                null,
                null,
                null,
                13
        );

        Double kda = ReflectionTestUtils.invokeMethod(pubgApiClient, "resolveRankedKda", stats);

        assertThat(kda).isZero();
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
