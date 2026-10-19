package com.gamerin.backend.domain.riot.client;

import java.time.Duration;
import java.util.List;

import com.gamerin.backend.domain.riot.dto.external.LeagueEntryResponse;
import com.gamerin.backend.domain.riot.dto.external.MatchResponse;
import com.gamerin.backend.domain.riot.dto.external.RiotAccountResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RiotApiClient {

    private final String asiaBaseUrl;
    private final String krBaseUrl;
    private final RestClient restClient;
    private final boolean apiKeyConfigured;

    public RiotApiClient(
            @Value("${riot.api.key:}") String apiKey,
            @Value("${riot.api.asia-base-url:https://asia.api.riotgames.com}") String asiaBaseUrl,
            @Value("${riot.api.kr-base-url:https://kr.api.riotgames.com}") String krBaseUrl,
            @Value("${riot.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${riot.api.read-timeout:10s}") Duration readTimeout
    ) {
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        this.asiaBaseUrl = asiaBaseUrl;
        this.krBaseUrl = krBaseUrl;
        ClientHttpRequestFactorySettings requestFactorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(requestFactorySettings))
                .defaultHeader("X-Riot-Token", apiKey)
                .build();
    }

    // 1. Riot ID와 태그로 계정 정보(PUUID) 찾기
    public RiotAccountResponse findAccount(String gameName, String tagLine) {
            validateApiKeyConfigured();
            try {
                return restClient.get()
                        .uri(asiaBaseUrl + "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}", gameName, tagLine)
                        .retrieve()
                        .body(RiotAccountResponse.class);
            } catch (HttpClientErrorException.NotFound e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 Riot ID 또는 태그입니다.");
            } catch (HttpClientErrorException.Forbidden e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Riot API Key가 만료되었거나 권한이 없습니다.");
            } catch (HttpClientErrorException.TooManyRequests e) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Riot API 호출 제한을 초과했습니다.");
            } catch (RestClientException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Riot API 연동 중 오류가 발생했습니다.");
            }
        }

    // 2. [최신 변경] PUUID로 리그 정보(티어, 전적) 직접 조회하기
    public List<LeagueEntryResponse> findLeagueEntriesByPuuid(String puuid) {
            validateApiKeyConfigured();
            try {
                return restClient.get()
                        .uri(krBaseUrl + "/lol/league/v4/entries/by-puuid/{puuid}",
  puuid)
                        .retrieve()
                        .body(new
  ParameterizedTypeReference<List<LeagueEntryResponse>>() {});
            } catch (RestClientException e) {
                e.printStackTrace();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Riot 리그 전적 조회 중 오류가 발생했습니다.", e);
            }
        }

    // 3. PUUID로 최근 매치 ID 목록 가져오기 (KDA 계산용)
    public List<String> findRecentMatchIds(String puuid, int count) {
            validateApiKeyConfigured();
            try {
                return restClient.get()
                        .uri(asiaBaseUrl + "/lol/match/v5/matches/by-puuid/{puuid}/ids?start=0&count={count}", puuid, count)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<String>>() {});
            } catch (RestClientException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Riot 매치 목록 조회 중 오류가 발생했습니다.");
            }
        }

    // 4. 매치 ID로 매치 상세 정보 가져오기
    public MatchResponse findMatchDetail(String matchId) {
            validateApiKeyConfigured();
            try {
                return restClient.get()
                        .uri(asiaBaseUrl + "/lol/match/v5/matches/{matchId}", matchId)
                        .retrieve()
                        .body(MatchResponse.class);
            } catch (RestClientException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Riot 매치 상세 정보 조회 중 오류가 발생했습니다.");
            }
        }

    private void validateApiKeyConfigured() {
            if (!apiKeyConfigured) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Riot API Key가 설정되지 않았습니다.");
            }
        }
}