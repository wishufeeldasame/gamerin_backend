package com.gamerin.backend.domain.r6.client;

import java.net.URI;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String PLATFORM_FAMILY = "pc";
    private static final String ALL_MODES = "all";
    private static final String RANKED_MODE = "ranked";
    private static final String RANKED_GAME_MODE = "pvp_ranked";
    private static final String QUICKPLAY_GAME_MODE = "pvp_quickplay";
    private static final String CASUAL_GAME_MODE = "pvp_casual";
    private static final String STANDARD_GAME_MODE = "pvp_standard";
    private static final String UNRANKED_GAME_MODE = "pvp_unranked";
    private static final String CONFIGURATION_ERROR_MESSAGE = "R6 stats API is not configured.";

    private final RestClient restClient;
    private final boolean configured;

    public R6DataStatsClient(
            @Value("${r6.api.key:}") String apiKey,
            @Value("${r6.api.base-url:https://api.r6data.com}") String baseUrl
    ) {
        String configuredApiKey = trimToNull(apiKey);
        String configuredBaseUrl = trimToNull(baseUrl);
        this.configured = configuredApiKey != null && configuredBaseUrl != null;

        RestClient.Builder builder = RestClient.builder();
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
        String accountId = readText(fullStats.at("/data/platformInfo/platformUserId"));
        if (accountId == null) {
            throw unexpectedResponse();
        }

        String externalPlayerName = readText(fullStats.at("/data/platformInfo/platformUserHandle"));
        StatsSnapshot snapshot = extractCurrentRankedSnapshot(fullStats);
        if (!snapshot.present()) {
            snapshot = extractNormalSnapshot(fullStats);
        }
        String tierLabel = snapshot.tierLabel();
        if (snapshot.ranked() && tierLabel == null) {
            tierLabel = extractLatestTierLabel(fetchStats("seasonalStats", requestedPlayerName, null));
        }

        return new R6Profile(
                externalPlayerName == null ? requestedPlayerName : externalPlayerName,
                accountId,
                new R6SummaryStats(
                        tierLabel,
                        snapshot.kd(),
                        snapshot.winRate(),
                        snapshot.matches()
                )
        );
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

    private StatsSnapshot extractCurrentRankedSnapshot(JsonNode fullStats) {
        Integer currentSeason = firstInteger(
                fullStats.at("/data/metadata/currentSeason"),
                fullStats.get("seasonNumber")
        );
        JsonNode segment = findCurrentRankedSegment(fullStats.at("/data/segments"), currentSeason);
        if (currentSeason == null && segment != null) {
            currentSeason = readInteger(segment.path("attributes").path("season"));
        }
        JsonNode boardProfile = findCurrentRankedBoardProfile(
                fullStats.path("platform_families_full_profiles"),
                currentSeason
        );

        if (segment == null && boardProfile == null) {
            return StatsSnapshot.empty();
        }

        Double kd = readStatDouble(segment, "kdRatio");
        Integer matches = readStatInteger(segment, "matchesPlayed");
        Integer wins = readBoardInteger(boardProfile, "wins");
        Integer losses = readBoardInteger(boardProfile, "losses");

        if (wins == null) {
            wins = firstStatInteger(segment, "wins", "matchesWon");
        }
        if (losses == null) {
            losses = firstStatInteger(segment, "losses", "matchesLost");
        }
        if (matches == null && wins != null && losses != null) {
            Integer abandons = readBoardInteger(boardProfile, "abandon");
            matches = wins + losses + (abandons == null ? 0 : abandons);
        }
        if (matches == null || matches == 0) {
            return StatsSnapshot.empty();
        }
        if (matches < 0) {
            throw unexpectedResponse();
        }

        Double winRate = calculateWinRate(wins, losses);
        String tierLabel = firstText(
                statMetadata(segment, "rank", "rankName"),
                statMetadata(segment, "rankPoints", "rankName"),
                boardProfile == null ? null : boardProfile.path("profile").path("rank_name")
        );
        return StatsSnapshot.ranked(tierLabel, kd, winRate, matches);
    }

    private StatsSnapshot extractNormalSnapshot(JsonNode fullStats) {
        JsonNode segment = findPreferredNormalSegment(fullStats.at("/data/segments"));
        if (segment == null) {
            return StatsSnapshot.empty();
        }

        Integer matches = resolveNormalMatches(segment);
        if (matches == null || matches == 0) {
            return StatsSnapshot.empty();
        }
        if (matches < 0) {
            throw unexpectedResponse();
        }

        Integer wins = firstStatInteger(segment, "wins", "matchesWon");
        Integer losses = firstStatInteger(segment, "losses", "matchesLost");
        return StatsSnapshot.normal(
                readStatDouble(segment, "kdRatio"),
                calculateWinRate(wins, losses),
                matches
        );
    }

    private JsonNode findPreferredNormalSegment(JsonNode segments) {
        if (!segments.isArray()) {
            return null;
        }

        JsonNode selected = null;
        int selectedPriority = 0;
        Integer selectedMatches = null;
        Integer selectedSeason = null;
        for (JsonNode segment : segments) {
            JsonNode attributes = segment.path("attributes");
            String platform = readText(attributes.path("platform"));
            if (platform != null && !PLATFORM_FAMILY.equalsIgnoreCase(platform)) {
                continue;
            }

            int priority = normalModePriority(readText(attributes.path("gamemode")));
            if (priority == 0) {
                continue;
            }
            Integer matches = resolveNormalMatches(segment);
            if (matches == null || matches <= 0) {
                continue;
            }
            Integer season = readInteger(attributes.path("season"));
            if (selected == null
                    || priority > selectedPriority
                    || (priority == selectedPriority && compareNullable(matches, selectedMatches) > 0)
                    || (priority == selectedPriority
                    && matches.equals(selectedMatches)
                    && compareNullable(season, selectedSeason) > 0)) {
                selected = segment;
                selectedPriority = priority;
                selectedMatches = matches;
                selectedSeason = season;
            }
        }
        return selected;
    }

    private int normalModePriority(String gameMode) {
        if (QUICKPLAY_GAME_MODE.equalsIgnoreCase(gameMode)) {
            return 2;
        }
        if (CASUAL_GAME_MODE.equalsIgnoreCase(gameMode)
                || STANDARD_GAME_MODE.equalsIgnoreCase(gameMode)
                || UNRANKED_GAME_MODE.equalsIgnoreCase(gameMode)) {
            return 1;
        }
        return 0;
    }

    private Integer resolveNormalMatches(JsonNode segment) {
        Integer matches = readStatInteger(segment, "matchesPlayed");
        if (matches != null) {
            return matches;
        }
        Integer wins = firstStatInteger(segment, "wins", "matchesWon");
        Integer losses = firstStatInteger(segment, "losses", "matchesLost");
        if (wins == null || losses == null) {
            return null;
        }
        Integer abandons = firstStatInteger(segment, "abandons", "abandon", "matchesAbandoned");
        return wins + losses + (abandons == null ? 0 : abandons);
    }

    private JsonNode findCurrentRankedSegment(JsonNode segments, Integer currentSeason) {
        if (!segments.isArray()) {
            return null;
        }

        JsonNode selected = null;
        Integer selectedSeason = null;
        Double selectedRankPoints = null;
        for (JsonNode segment : segments) {
            JsonNode attributes = segment.path("attributes");
            if (!RANKED_GAME_MODE.equalsIgnoreCase(readText(attributes.path("gamemode")))) {
                continue;
            }
            String platform = readText(attributes.path("platform"));
            if (platform != null && !PLATFORM_FAMILY.equalsIgnoreCase(platform)) {
                continue;
            }
            String sessionType = readText(attributes.path("sessionType"));
            if (sessionType != null && !RANKED_MODE.equalsIgnoreCase(sessionType)) {
                continue;
            }

            Integer season = readInteger(attributes.path("season"));
            if (currentSeason != null && !currentSeason.equals(season)) {
                continue;
            }
            Double rankPoints = readStatDouble(segment, "rankPoints");
            if (selected == null
                    || (currentSeason == null && compareNullable(season, selectedSeason) > 0)
                    || (equalsNullable(season, selectedSeason)
                    && compareNullable(rankPoints, selectedRankPoints) > 0)) {
                selected = segment;
                selectedSeason = season;
                selectedRankPoints = rankPoints;
            }
        }
        return selected;
    }

    private JsonNode findCurrentRankedBoardProfile(JsonNode platformProfiles, Integer currentSeason) {
        if (!platformProfiles.isArray()) {
            return null;
        }

        JsonNode selected = null;
        Integer selectedSeason = null;
        for (JsonNode platformProfile : platformProfiles) {
            JsonNode boards = platformProfile.path("board_ids_full_profiles");
            if (!boards.isArray()) {
                continue;
            }
            for (JsonNode board : boards) {
                if (!RANKED_MODE.equalsIgnoreCase(readText(board.path("board_id")))) {
                    continue;
                }
                JsonNode fullProfiles = board.path("full_profiles");
                if (!fullProfiles.isArray()) {
                    continue;
                }
                for (JsonNode fullProfile : fullProfiles) {
                    Integer season = readInteger(fullProfile.path("season_id"));
                    if (currentSeason != null && !currentSeason.equals(season)) {
                        continue;
                    }
                    if (selected == null || compareNullable(season, selectedSeason) > 0) {
                        selected = fullProfile;
                        selectedSeason = season;
                    }
                }
            }
        }
        return selected;
    }

    private String extractLatestTierLabel(JsonNode seasonalStats) {
        JsonNode historyEntries = seasonalStats.at("/data/history/data");
        if (!historyEntries.isArray()) {
            return null;
        }

        String selectedRank = null;
        OffsetDateTime selectedAt = null;
        for (JsonNode entry : historyEntries) {
            if (!entry.isArray() || entry.size() < 2) {
                continue;
            }
            String rank = readText(entry.get(1).path("metadata").path("rank"));
            if (rank == null) {
                continue;
            }
            OffsetDateTime recordedAt = parseDateTime(readText(entry.get(0)));
            if (selectedRank == null
                    || (recordedAt != null && (selectedAt == null || recordedAt.isAfter(selectedAt)))) {
                selectedRank = rank;
                selectedAt = recordedAt;
            }
        }
        return selectedRank;
    }

    private JsonNode statMetadata(JsonNode segment, String statName, String metadataName) {
        if (segment == null) {
            return null;
        }
        return segment.path("stats").path(statName).path("metadata").path(metadataName);
    }

    private Double readStatDouble(JsonNode segment, String statName) {
        if (segment == null) {
            return null;
        }
        return readDouble(segment.path("stats").path(statName).path("value"));
    }

    private Integer readStatInteger(JsonNode segment, String statName) {
        if (segment == null) {
            return null;
        }
        return readInteger(segment.path("stats").path(statName).path("value"));
    }

    private Integer firstStatInteger(JsonNode segment, String... statNames) {
        for (String statName : statNames) {
            Integer value = readStatInteger(segment, statName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer readBoardInteger(JsonNode boardProfile, String fieldName) {
        if (boardProfile == null) {
            return null;
        }
        return readInteger(boardProfile.path("profile").path(fieldName));
    }

    private Double calculateWinRate(Integer wins, Integer losses) {
        if (wins == null || losses == null || wins < 0 || losses < 0) {
            return null;
        }
        int completedMatches = wins + losses;
        return completedMatches == 0 ? null : wins * 100.0 / completedMatches;
    }

    private Integer firstInteger(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            Integer value = readInteger(node);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = readText(node);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return trimToNull(node.asText(null));
    }

    private Double readDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        String value = trimToNull(node.asText(null));
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw unexpectedResponse();
        }
    }

    private Integer readInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        String value = trimToNull(node.asText(null));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw unexpectedResponse();
        }
    }

    private OffsetDateTime parseDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T extends Comparable<T>> int compareNullable(T left, T right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    private boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
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

    private record StatsSnapshot(
            boolean present,
            boolean ranked,
            String tierLabel,
            Double kd,
            Double winRate,
            Integer matches
    ) {

        private static StatsSnapshot ranked(String tierLabel, Double kd, Double winRate, Integer matches) {
            return new StatsSnapshot(true, true, tierLabel, kd, winRate, matches);
        }

        private static StatsSnapshot normal(Double kd, Double winRate, Integer matches) {
            return new StatsSnapshot(true, false, null, kd, winRate, matches);
        }

        private static StatsSnapshot empty() {
            return new StatsSnapshot(false, false, null, null, null, null);
        }
    }
}
