package com.gamerin.backend.domain.r6.client;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

final class R6DataStatsParser {

    private static final String PLATFORM_FAMILY = "pc";
    private static final String RANKED_MODE = "ranked";
    private static final String RANKED_GAME_MODE = "pvp_ranked";
    private static final String QUICKPLAY_GAME_MODE = "pvp_quickplay";
    private static final String CASUAL_GAME_MODE = "pvp_casual";
    private static final String STANDARD_GAME_MODE = "pvp_standard";
    private static final String UNRANKED_GAME_MODE = "pvp_unranked";

    ParsedFullStats parseFullStats(JsonNode fullStats) {
        String accountId = readText(fullStats.at("/data/platformInfo/platformUserId"));
        String playerName = readText(fullStats.at("/data/platformInfo/platformUserHandle"));

        StatsSnapshot snapshot = extractCurrentRankedSnapshot(fullStats);
        if (!snapshot.present()) {
            snapshot = extractNormalSnapshot(fullStats);
        }

        return new ParsedFullStats(
                accountId,
                playerName,
                snapshot.ranked(),
                snapshot.tierLabel(),
                snapshot.kd(),
                snapshot.winRate(),
                snapshot.matches()
        );
    }

    String parseLatestTierLabel(JsonNode seasonalStats) {
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
            throw new InvalidResponseException();
        }

        Double winRate = calculateWinRate(wins, matches);
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
            throw new InvalidResponseException();
        }

        Integer wins = firstStatInteger(segment, "wins", "matchesWon");
        return StatsSnapshot.normal(
                readStatDouble(segment, "kdRatio"),
                calculateWinRate(wins, matches),
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

    private Double calculateWinRate(Integer wins, Integer matches) {
        if (wins == null || matches == null || wins < 0 || matches <= 0 || wins > matches) {
            return null;
        }
        return wins * 100.0 / matches;
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
            throw new InvalidResponseException();
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
            throw new InvalidResponseException();
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }

    record ParsedFullStats(
            String accountId,
            String playerName,
            boolean ranked,
            String tierLabel,
            Double kd,
            Double winRate,
            Integer matches
    ) {
    }

    static final class InvalidResponseException extends RuntimeException {
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
