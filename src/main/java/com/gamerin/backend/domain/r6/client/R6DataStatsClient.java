package com.gamerin.backend.domain.r6.client;

import java.net.URI;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class R6DataStatsClient implements R6StatsClient {

    private static final String API_KEY_HEADER = "api-key";
    private static final String STATS_PATH = "/api/stats";
    private static final String PLATFORM_TYPE = "uplay";
    private static final String ALL_MODES = "all";
    private static final String CONFIGURATION_ERROR_MESSAGE = "R6 stats API is not configured.";

    private final RestClient restClient;
    private final R6DataStatsParser parser;
    private final boolean configured;

    public R6DataStatsClient(
            @Value("${r6.api.key:}") String apiKey,
            @Value("${r6.api.base-url:https://api.r6data.com}") String baseUrl,
            @Value("${r6.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${r6.api.read-timeout:10s}") Duration readTimeout
    ) {
        String configuredApiKey = trimToNull(apiKey);
        String configuredBaseUrl = trimToNull(baseUrl);
        this.configured = configuredApiKey != null && configuredBaseUrl != null;
        this.parser = new R6DataStatsParser();

        ClientHttpRequestFactorySettings requestFactorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(requestFactorySettings));
        if (configuredBaseUrl != null) {
            builder.baseUrl(configuredBaseUrl);
        }
        if (configuredApiKey != null) {
            builder.defaultHeader(API_KEY_HEADER, configuredApiKey);
        }
        this.restClient = builder.build();
    }

    @Override
    public R6Profile findProfile(String playerName) {
        return fetchProfile(playerName);
    }

    @Override
    public R6SummaryStats getSummary(R6ProfileRef profileRef) {
        if (profileRef == null
                || trimToNull(profileRef.playerName()) == null
                || trimToNull(profileRef.accountId()) == null) {
            throw unexpectedResponse();
        }

        R6Profile refreshedProfile = fetchProfile(profileRef.playerName());
        if (!profileRef.accountId().strip().equalsIgnoreCase(refreshedProfile.accountId().strip())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Connected R6 account no longer matches the player name."
            );
        }
        return refreshedProfile.summary();
    }

    private R6Profile fetchProfile(String playerName) {
        validateConfigured();
        String requestedPlayerName = trimToNull(playerName);
        if (requestedPlayerName == null) {
            throw unexpectedResponse();
        }

        JsonNode fullStats = fetchStats("fullStats", requestedPlayerName, ALL_MODES);
        R6DataStatsParser.ParsedFullStats parsedStats = parseFullStats(fullStats);
        String accountId = trimToNull(parsedStats.accountId());
        if (accountId == null) {
            throw unexpectedResponse();
        }

        String tierLabel = parsedStats.tierLabel();
        if (parsedStats.ranked() && tierLabel == null) {
            JsonNode seasonalStats = fetchStats("seasonalStats", requestedPlayerName, null);
            tierLabel = parseLatestTierLabel(seasonalStats);
        }

        return new R6Profile(
                parsedStats.playerName() == null ? requestedPlayerName : parsedStats.playerName(),
                accountId,
                new R6SummaryStats(
                        tierLabel,
                        parsedStats.kd(),
                        parsedStats.winRate(),
                        parsedStats.matches(),
                        parsedStats.matches() == null
                                ? null
                                : parsedStats.ranked() ? GameStatsMode.RANKED : GameStatsMode.NORMAL
                )
        );
    }

    private R6DataStatsParser.ParsedFullStats parseFullStats(JsonNode fullStats) {
        try {
            return parser.parseFullStats(fullStats);
        } catch (R6DataStatsParser.InvalidResponseException e) {
            throw unexpectedResponse();
        }
    }

    private String parseLatestTierLabel(JsonNode seasonalStats) {
        try {
            return parser.parseLatestTierLabel(seasonalStats);
        } catch (R6DataStatsParser.InvalidResponseException e) {
            throw unexpectedResponse();
        }
    }

    private JsonNode fetchStats(String type, String playerName, String modes) {
        try {
            JsonNode response = restClient.get()
                    .uri(buildStatsEndpoint(type, playerName, modes))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || response.isNull()) {
                throw unexpectedResponse();
            }
            return response;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "R6 public profile not found.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "R6 stats API rate limit exceeded.");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CONFIGURATION_ERROR_MESSAGE);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch R6 public profile.");
        }
    }

    private URI buildStatsEndpoint(String type, String playerName, String modes) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(STATS_PATH)
                .queryParam("type", type)
                .queryParam("nameOnPlatform", "{playerName}")
                .queryParam("platformType", PLATFORM_TYPE);
        if (modes != null) {
            builder.queryParam("modes", modes);
        }
        return builder.encode().buildAndExpand(playerName).toUri();
    }

    private void validateConfigured() {
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CONFIGURATION_ERROR_MESSAGE);
        }
    }

    private ResponseStatusException unexpectedResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected R6 stats API response.");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
