package com.gamerin.backend.domain.r6.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class R6DataStatsParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final R6DataStatsParser parser = new R6DataStatsParser();

    @Test
    void parsesCurrentPcRankedSnapshotAndLatestSeasonalTier() throws IOException {
        R6DataStatsParser.ParsedFullStats stats = parser.parseFullStats(fixture("full-stats-ranked.json"));

        assertThat(stats.accountId()).isEqualTo("account-41");
        assertThat(stats.playerName()).isEqualTo("CanonicalPlayer");
        assertThat(stats.ranked()).isTrue();
        assertThat(stats.tierLabel()).isNull();
        assertThat(stats.kd()).isEqualTo(1.05);
        assertThat(stats.matches()).isEqualTo(117);
        assertThat(stats.winRate()).isCloseTo(54.700854701, within(0.000000001));

        assertThat(parser.parseLatestTierLabel(fixture("seasonal-stats.json")))
                .isEqualTo("EMERALD II");
    }

    @Test
    void returnsEmptySnapshotWhenCurrentRankedAndNormalStatsAreAbsent() throws IOException {
        R6DataStatsParser.ParsedFullStats stats = parser.parseFullStats(fixture("full-stats-unranked.json"));

        assertThat(stats.accountId()).isEqualTo("unranked-account");
        assertThat(stats.ranked()).isFalse();
        assertThat(stats.tierLabel()).isNull();
        assertThat(stats.kd()).isNull();
        assertThat(stats.winRate()).isNull();
        assertThat(stats.matches()).isNull();
    }

    @Test
    void fallsBackToAggregatedQuickplayBeforeNormalAliases() throws IOException {
        R6DataStatsParser.ParsedFullStats stats = parser.parseFullStats(
                fixture("full-stats-normal-fallback.json")
        );

        assertThat(stats.ranked()).isFalse();
        assertThat(stats.tierLabel()).isNull();
        assertThat(stats.kd()).isEqualTo(1.41);
        assertThat(stats.winRate()).isEqualTo(48.0);
        assertThat(stats.matches()).isEqualTo(50);
    }

    @Test
    void selectsLargestNormalAliasWhenQuickplayAggregateIsMissing() throws IOException {
        R6DataStatsParser.ParsedFullStats stats = parser.parseFullStats(
                fixture("full-stats-normal-alias-only.json")
        );

        assertThat(stats.ranked()).isFalse();
        assertThat(stats.kd()).isEqualTo(1.48);
        assertThat(stats.winRate()).isCloseTo(52.380952381, within(0.000000001));
        assertThat(stats.matches()).isEqualTo(42);
    }

    @Test
    void rejectsMalformedRankedNumericValue() throws IOException {
        JsonNode malformed = objectMapper.readTree(fixtureText("full-stats-ranked.json").replace(
                "\"kdRatio\": { \"value\": 1.05 }",
                "\"kdRatio\": { \"value\": \"invalid\" }"
        ));

        assertThatThrownBy(() -> parser.parseFullStats(malformed))
                .isInstanceOf(R6DataStatsParser.InvalidResponseException.class);
    }

    private JsonNode fixture(String name) throws IOException {
        return objectMapper.readTree(fixtureText(name));
    }

    private String fixtureText(String name) throws IOException {
        String path = "/fixtures/r6/" + name;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(path), path)) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
