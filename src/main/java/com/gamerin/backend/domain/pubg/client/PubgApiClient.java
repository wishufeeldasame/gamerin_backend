package com.gamerin.backend.domain.pubg.client;

import com.gamerin.backend.domain.pubg.dto.external.PubgPlayerLookupResponse;
import com.gamerin.backend.domain.pubg.dto.external.PubgNormalStatsResponse;
import com.gamerin.backend.domain.pubg.dto.external.PubgRankedStatsResponse;
import com.gamerin.backend.domain.pubg.dto.external.PubgSeasonListResponse;
import com.gamerin.backend.domain.pubg.dto.external.NormalGameModeStats;
import com.gamerin.backend.domain.pubg.dto.external.RankedGameModeStats;
import com.gamerin.backend.domain.pubg.model.NormalStats;
import com.gamerin.backend.domain.pubg.model.RankedStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PubgApiClient {

    private static final String PLATFORM = "steam";
    private static final String BASE_URL = "https://api.pubg.com";

    private final RestClient restClient;
    private final boolean apiKeyConfigured;

    public PubgApiClient(@Value("${pubg.api.key:}") String apiKey) {
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Accept", "application/vnd.api+json")
                .build();
    }

    public String findAccountId(String playerName) {
        validateApiKeyConfigured();
        try {
            PubgPlayerLookupResponse response = restClient.get()
                    .uri("/shards/{platform}/players?filter[playerNames]={playerName}", PLATFORM, playerName)
                    .retrieve()
                    .body(PubgPlayerLookupResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PUBG player not found.");
            }

            return response.data().get(0).id();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PUBG player not found.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PUBG API rate limit exceeded.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch PUBG player information.");
        }
    }

    public String findCurrentSeasonId() {
        validateApiKeyConfigured();
        try {
            PubgSeasonListResponse response = restClient.get()
                    .uri("/shards/{platform}/seasons", PLATFORM)
                    .retrieve()
                    .body(PubgSeasonListResponse.class);

            if (response == null || response.data() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to load PUBG season information.");
            }

            return response.data().stream()
                    .filter(season -> season.attributes().isCurrentSeason())
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Current PUBG season not found."))
                    .id();
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PUBG API rate limit exceeded.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch PUBG season information.");
        }
    }

    public RankedStats getRankedStats(String accountId, String seasonId, String mode) {
        validateApiKeyConfigured();
        try {
            PubgRankedStatsResponse response = restClient.get()
                    .uri("/shards/{platform}/players/{accountId}/seasons/{seasonId}/ranked",
                            PLATFORM, accountId, seasonId)
                    .retrieve()
                    .body(PubgRankedStatsResponse.class);

            if (response == null || response.data() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to load PUBG ranked stats.");
            }

            RankedGameModeStats modeStats = response.data()
                    .attributes()
                    .rankedGameModeStats()
                    .get(mode);

            if (modeStats == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ranked stats found for the selected mode.");
            }

            return new RankedStats(
                    modeStats.kda(),
                    modeStats.roundsPlayed(),
                    modeStats.wins(),
                    modeStats.currentTier().tier(),
                    modeStats.currentTier().subTier()
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PUBG ranked season record not found.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PUBG API rate limit exceeded.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch PUBG ranked stats.");
        }
    }

    public NormalStats getNormalStats(String accountId, String seasonId, String mode) {
        validateApiKeyConfigured();
        try {
            PubgNormalStatsResponse response = restClient.get()
                    .uri("/shards/{platform}/players/{accountId}/seasons/{seasonId}",
                            PLATFORM, accountId, seasonId)
                    .retrieve()
                    .body(PubgNormalStatsResponse.class);

            if (response == null || response.data() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to load PUBG normal stats.");
            }

            NormalGameModeStats modeStats = response.data()
                    .attributes()
                    .gameModeStats()
                    .get(mode);

            if (modeStats == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No normal stats found for the selected mode.");
            }

            return new NormalStats(
                    resolveNormalKda(modeStats),
                    nullSafe(modeStats.roundsPlayed()),
                    nullSafe(modeStats.wins())
            );
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PUBG normal season record not found.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PUBG API rate limit exceeded.");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch PUBG normal stats.");
        }
    }

    private void validateApiKeyConfigured() {
        if (!apiKeyConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PUBG API key is not configured.");
        }
    }

    private double resolveNormalKda(NormalGameModeStats modeStats) {
        if (modeStats.kda() != null) {
            return modeStats.kda();
        }

        Integer kills = modeStats.kills();
        Integer losses = modeStats.losses();

        if (kills == null || losses == null) {
            return 0.0;
        }

        if (losses == 0) {
            return kills.doubleValue();
        }

        return kills.doubleValue() / losses;
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
