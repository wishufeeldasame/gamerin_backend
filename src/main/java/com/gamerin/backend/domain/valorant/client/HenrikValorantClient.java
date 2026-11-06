package com.gamerin.backend.domain.valorant.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
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
    private static final String CONFIGURATION_ERROR_MESSAGE = "발로란트 전적 API가 설정되지 않았습니다.";

    private final RestClient restClient;
    private final boolean configured;

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
            return new ValorantRank(null, null, null);
        }
        JsonNode current = response.path("data").path("current");
        return new ValorantRank(
                trimToNull(current.path("tier").path("name").asText(null)),
                trimToNull(response.path("data").path("peak").path("tier").path("name").asText(null)),
                current.path("rr").isInt() ? current.path("rr").asInt() : null
        );
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
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "발로란트 전적 API 호출 제한을 초과했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, CONFIGURATION_ERROR_MESSAGE);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "발로란트 전적 조회 중 오류가 발생했습니다.");
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
