package com.gamerin.backend.domain.valorant.client;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchReference;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.valorant.model.ValorantRank;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** HenrikDev 비공식 Valorant API(https://api.henrikdev.xyz) 클라이언트. 플랫폼은 PC만 조회한다. */
@Component
public class HenrikValorantClient {

    private static final String PLATFORM = "pc";
    private static final String COMPETITIVE = "competitive";
    // v4 matches는 10을 넘는 size를 10으로 자른다.
    private static final int MATCH_COUNT = 10;
    // 실측(2026-11): 분당 30회 한도에서 10경기 페이지 1회가 최대 10을 소모한다.
    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 60;
    private static final long MAX_RETRY_AFTER_SECONDS = 65;
    private static final String CONFIGURATION_ERROR_MESSAGE = "발로란트 전적 API가 설정되지 않았습니다.";

    private final RestClient restClient;
    private final boolean configured;
    // 테스트에서 대기를 건너뛰기 위해 바꾼다.
    LongConsumer pause = HenrikValorantClient::sleep;

    public HenrikValorantClient(
            @Value("${henrik.api.key:}") String apiKey,
            @Value("${henrik.api.base-url:https://api.henrikdev.xyz}") String baseUrl,
            @Value("${henrik.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${henrik.api.read-timeout:10s}") Duration readTimeout
    ) {
        String configuredApiKey = trimToNull(apiKey);
        String configuredBaseUrl = trimToNull(baseUrl);
        this.configured = configuredApiKey != null && configuredBaseUrl != null;

        ClientHttpRequestFactorySettings requestFactorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(requestFactorySettings));
        if (configuredBaseUrl != null) {
            builder.baseUrl(configuredBaseUrl);
        }
        if (configuredApiKey != null) {
            builder.defaultHeader("Authorization", configuredApiKey);
        }
        this.restClient = builder.build();
    }

    public ValorantAccount findAccount(String name, String tag) {
        JsonNode data = fetch(true, "/valorant/v2/account/{name}/{tag}", name, tag).path("data");
        String puuid = trimToNull(data.path("puuid").asText(null));
        if (puuid == null) {
            throw unexpectedResponse();
        }
        String canonicalName = trimToNull(data.path("name").asText(null));
        String canonicalTag = trimToNull(data.path("tag").asText(null));
        return new ValorantAccount(
                puuid,
                canonicalName == null ? name : canonicalName,
                canonicalTag == null ? tag : canonicalTag
        );
    }

    /** 랭크 기록이 없으면(404) 티어 없음으로 본다. */
    public ValorantRank getRank(ValorantRegion region, String puuid) {
        JsonNode response = fetch(false, "/valorant/v3/by-puuid/mmr/{affinity}/{platform}/{puuid}",
                region.affinity(), PLATFORM, puuid);
        if (response == null) {
            return new ValorantRank(null, null, null, null);
        }
        JsonNode data = response.path("data");
        JsonNode current = data.path("current");
        return new ValorantRank(
                trimToNull(current.path("tier").path("name").asText(null)),
                trimToNull(data.path("peak").path("tier").path("name").asText(null)),
                current.path("rr").isInt() ? current.path("rr").asInt() : null,
                currentRiotId(data.path("account"), puuid)
        );
    }

    // 닉네임 변경을 반영하되 다른 계정의 이름을 저장하지 않도록 puuid가 같을 때만 쓴다.
    private String currentRiotId(JsonNode account, String puuid) {
        String name = trimToNull(account.path("name").asText(null));
        String tag = trimToNull(account.path("tag").asText(null));
        if (name == null || tag == null || !puuid.equalsIgnoreCase(account.path("puuid").asText(""))) {
            return null;
        }
        return name + "#" + tag;
    }

    /** 최근 경쟁전 최대 10경기. 경기 기록이 없으면(404) 빈 목록이다. */
    public List<ValorantMatchStats> getRecentCompetitiveMatches(ValorantRegion region, String puuid) {
        JsonNode response = fetch(false,
                "/valorant/v4/by-puuid/matches/{affinity}/{platform}/{puuid}?mode={mode}&size={size}",
                region.affinity(), PLATFORM, puuid, COMPETITIVE, MATCH_COUNT);
        List<ValorantMatchStats> matches = new ArrayList<>();
        if (response == null) {
            return matches;
        }
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            throw unexpectedResponse();
        }
        for (JsonNode match : data) {
            ValorantMatchStats stats = toMatchStats(match, puuid);
            if (stats != null) {
                matches.add(stats);
            }
        }
        return matches;
    }

    /**
     * since 이후에 시작한 경쟁전 경기를 기간 경계까지 최신순 페이지(start)를 넘기며 읽는다.
     * 429는 Retry-After만큼 기다린 뒤 같은 페이지를 다시 요청한다.
     */
    public List<ValorantMatchDetail> getCompetitiveMatchesSince(ValorantRegion region, String puuid, Instant since) {
        Map<String, ValorantMatchDetail> matches = new LinkedHashMap<>();
        Set<String> seenMatchIds = new HashSet<>();
        for (int page = 0; ; page++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "발로란트 전적 조회가 중단되었습니다.");
            }
            JsonNode response = fetchPage(region, puuid, page * MATCH_COUNT);
            if (response == null) {
                break;
            }
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                throw unexpectedResponse();
            }
            boolean reachedSince = false;
            boolean advanced = false;
            for (JsonNode match : data) {
                String matchId = trimToNull(match.path("metadata").path("match_id").asText(null));
                Instant startedAt = parseInstant(match.path("metadata").path("started_at").asText(null));
                if (matchId == null || startedAt == null) {
                    throw unexpectedResponse();
                }
                advanced |= seenMatchIds.add(matchId);
                if (startedAt.isBefore(since)) {
                    reachedSince = true;
                    continue;
                }
                ValorantMatchDetail detail = toMatchDetail(match, puuid, startedAt);
                if (detail != null) {
                    matches.putIfAbsent(detail.matchId(), detail);
                }
            }
            if (reachedSince || data.size() < MATCH_COUNT) {
                break;
            }
            // start를 무시하거나 조회 상한에서 마지막 페이지를 반복하면 불완전한 통계를 저장하지 않는다.
            if (!advanced) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "발로란트 경기 기록의 수집 범위를 확인할 수 없습니다.");
            }
        }
        return new ArrayList<>(matches.values());
    }

    /** 최근 최대 20건의 랭크 이력으로 대조한다. 기간 전체의 완전성을 보장하는 목록은 아니다. */
    public List<ValorantMatchReference> getCompetitiveMatchHistory(ValorantRegion region, String puuid) {
        JsonNode response = fetchWithRetry("/valorant/v2/by-puuid/mmr-history/{affinity}/{platform}/{puuid}",
                region.affinity(), PLATFORM, puuid);
        if (response == null) {
            return List.of();
        }
        JsonNode history = response.path("data").path("history");
        if (!history.isArray()) {
            throw unexpectedResponse();
        }
        Map<String, ValorantMatchReference> references = new LinkedHashMap<>();
        for (JsonNode item : history) {
            String matchId = trimToNull(item.path("match_id").asText(null));
            Instant startedAt = parseInstant(item.path("date").asText(null));
            if (matchId == null || startedAt == null) {
                throw unexpectedResponse();
            }
            references.putIfAbsent(matchId, new ValorantMatchReference(matchId, startedAt,
                    trimToNull(item.path("map").path("name").asText(null))));
        }
        return new ArrayList<>(references.values());
    }

    /** 목록에서 빠진 경기의 상세를 직접 확인한다. 404면 상세 미제공이다. */
    public Optional<ValorantMatchDetail> getMatchDetail(ValorantRegion region, String puuid, String matchId) {
        JsonNode response = fetchWithRetry("/valorant/v4/match/{affinity}/{matchId}", region.affinity(), matchId);
        if (response == null) {
            return Optional.empty();
        }
        JsonNode data = response.path("data");
        Instant startedAt = parseInstant(data.path("metadata").path("started_at").asText(null));
        ValorantMatchDetail detail = startedAt == null ? null : toMatchDetail(data, puuid, startedAt);
        if (detail == null || !matchId.equals(detail.matchId())) {
            throw unexpectedResponse();
        }
        return Optional.of(detail);
    }

    private JsonNode fetchPage(ValorantRegion region, String puuid, int start) {
        return fetchWithRetry(
                "/valorant/v4/by-puuid/matches/{affinity}/{platform}/{puuid}?mode={mode}&size={size}&start={start}",
                region.affinity(), PLATFORM, puuid, COMPETITIVE, MATCH_COUNT, start);
    }

    private JsonNode fetchWithRetry(String uri, Object... uriVariables) {
        for (int attempt = 0; ; attempt++) {
            try {
                return fetch(false, uri, uriVariables);
            } catch (RateLimitedException e) {
                if (attempt >= MAX_RATE_LIMIT_RETRIES) {
                    throw e;
                }
                pause.accept(e.retryAfterSeconds * 1000);
            }
        }
    }

    private ValorantMatchDetail toMatchDetail(JsonNode match, String puuid, Instant startedAt) {
        String matchId = trimToNull(match.path("metadata").path("match_id").asText(null));
        ValorantMatchStats stats = toMatchStats(match, puuid);
        if (matchId == null || stats == null) {
            return null;
        }
        JsonNode player = findBy(match.path("players"), "puuid", puuid);
        JsonNode tier = player.path("tier");
        return new ValorantMatchDetail(
                matchId,
                startedAt,
                trimToNull(match.path("metadata").path("map").path("name").asText(null)),
                trimToNull(player.path("agent").path("name").asText(null)),
                tier.path("id").isInt() ? tier.path("id").asInt() : null,
                trimToNull(tier.path("name").asText(null)),
                stats
        );
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ValorantMatchStats toMatchStats(JsonNode match, String puuid) {
        JsonNode metadata = match.path("metadata");
        // mode 필터를 믿지 않고 경쟁전·완료 경기만 다시 거른다.
        if (!COMPETITIVE.equalsIgnoreCase(metadata.path("queue").path("id").asText(""))
                || !metadata.path("is_completed").asBoolean(true)) {
            return null;
        }
        JsonNode player = findBy(match.path("players"), "puuid", puuid);
        if (player == null) {
            return null;
        }
        JsonNode team = findBy(match.path("teams"), "team_id", player.path("team_id").asText(""));
        if (team == null) {
            return null;
        }
        JsonNode stats = player.path("stats");
        JsonNode rounds = team.path("rounds");
        return new ValorantMatchStats(
                stats.path("kills").asInt(0),
                stats.path("deaths").asInt(0),
                stats.path("assists").asInt(0),
                stats.path("score").asInt(0),
                rounds.path("won").asInt(0) + rounds.path("lost").asInt(0),
                stats.path("headshots").asInt(0),
                stats.path("bodyshots").asInt(0),
                stats.path("legshots").asInt(0),
                team.path("won").asBoolean(false)
        );
    }

    private JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (!value.isEmpty() && value.equalsIgnoreCase(item.path(field).asText(""))) {
                return item;
            }
        }
        return null;
    }

    /** notFoundIsError가 false면 404를 null로 돌려준다. */
    private JsonNode fetch(boolean notFoundIsError, String uri, Object... uriVariables) {
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CONFIGURATION_ERROR_MESSAGE);
        }
        try {
            JsonNode response = restClient.get()
                    .uri(uri, uriVariables)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw unexpectedResponse();
            }
            return response;
        } catch (HttpClientErrorException.NotFound e) {
            if (!notFoundIsError) {
                return null;
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 Riot ID 또는 태그입니다.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RateLimitedException(retryAfterSeconds(e));
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CONFIGURATION_ERROR_MESSAGE);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "발로란트 전적 조회 중 오류가 발생했습니다.");
        }
    }

    private long retryAfterSeconds(HttpClientErrorException e) {
        String value = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        try {
            long seconds = value == null ? DEFAULT_RETRY_AFTER_SECONDS : Long.parseLong(value.strip());
            return Math.max(1, Math.min(seconds, MAX_RETRY_AFTER_SECONDS));
        } catch (NumberFormatException invalid) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "발로란트 전적 조회가 중단되었습니다.");
        }
    }

    private static final class RateLimitedException extends ResponseStatusException {
        private final long retryAfterSeconds;

        private RateLimitedException(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "발로란트 전적 API 호출 제한을 초과했습니다. 잠시 후 다시 시도해 주세요.");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private ResponseStatusException unexpectedResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "발로란트 전적 API 응답 형식이 올바르지 않습니다.");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
