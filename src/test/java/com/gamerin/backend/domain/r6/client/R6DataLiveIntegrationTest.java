package com.gamerin.backend.domain.r6.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.gamerin.backend.domain.r6.model.R6Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "R6DATA_LIVE_TEST", matches = "true")
class R6DataLiveIntegrationTest {

    @Test
    void mapsLiveProfileThroughR6DataClient() {
        String apiKey = System.getenv("R6DATA_API_KEY");
        String playerName = System.getenv("R6DATA_LIVE_PLAYER");
        String baseUrl = System.getenv().getOrDefault("R6DATA_API_BASE_URL", "https://api.r6data.com");

        assertThat(apiKey).isNotBlank();
        assertThat(playerName).isNotBlank();

        R6Profile profile = new R6DataStatsClient(
                apiKey,
                baseUrl,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10)
        ).findProfile(playerName);

        assertThat(profile.accountId()).isNotBlank();
        assertThat(profile.playerName()).isNotBlank();
        assertThat(profile.summary()).isNotNull();
        if (profile.summary().tierLabel() != null) {
            assertThat(profile.summary().tierLabel()).isNotBlank();
        }
        assertThat(profile.summary().kd()).isNotNegative();
        assertThat(profile.summary().winRate()).isBetween(0.0, 100.0);
        assertThat(profile.summary().matches()).isPositive();
    }
}
