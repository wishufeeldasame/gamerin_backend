package com.gamerin.backend.domain.valorant.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.valorant.model.ValorantRank;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HenrikValorantClientTest {

    private HttpServer server;
    private int status = 200;
    private String body = "{}";
    private long delayMillis;
    private final List<String> uris = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    // 요청 순서대로 쓸 {status, body, Retry-After}. 비면 status/body 필드를 쓴다.
    private final Deque<String[]> responses = new ArrayDeque<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void missingKeyReturns503WithoutRequest() {
        HenrikValorantClient client = new HenrikValorantClient("", baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertStatus(() -> client.findAccount("name", "KR1"), HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(uris).isEmpty();
    }

    @Test
    void findAccountSendsKeyAndEncodesRiotId() {
        body = """
                {"status":200,"data":{"puuid":"henrik-puuid","name":"한글 이름","tag":"KR1","region":"kr"}}
                """;

        var account = client().findAccount("한글 이름", "KR1");

        assertThat(account.puuid()).isEqualTo("henrik-puuid");
        assertThat(account.riotId()).isEqualTo("한글 이름#KR1");
        assertThat(authorizations).containsExactly("test-key");
        assertThat(uris.get(0)).isEqualTo("/valorant/v2/account/%ED%95%9C%EA%B8%80%20%EC%9D%B4%EB%A6%84/KR1");
    }

    @ParameterizedTest
    @CsvSource({"404,NOT_FOUND", "429,TOO_MANY_REQUESTS", "401,SERVICE_UNAVAILABLE", "403,SERVICE_UNAVAILABLE",
            "500,BAD_GATEWAY"})
    void findAccountMapsErrorStatus(int upstream, HttpStatus expected) {
        status = upstream;

        assertStatus(() -> client().findAccount("name", "KR1"), expected);
    }

    @Test
    void readTimeoutMapsTo502() {
        delayMillis = 1500;
        HenrikValorantClient client = new HenrikValorantClient("test-key", baseUrl(),
                Duration.ofSeconds(1), Duration.ofMillis(200));

        assertStatus(() -> client.findAccount("name", "KR1"), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getRankReadsCurrentAndPeakTier() {
        body = """
                {"status":200,"data":{"account":{"name":"New Name","tag":"KR2","puuid":"P"},
                 "current":{"tier":{"id":13,"name":"Gold 2"},"rr":41},
                 "peak":{"tier":{"id":16,"name":"Platinum 2"}}}}
                """;

        assertThat(client().getRank(ValorantRegion.AP, "p"))
                .isEqualTo(new ValorantRank("Gold 2", "Platinum 2", 41, "New Name#KR2"));
        assertThat(uris.get(0)).isEqualTo("/valorant/v3/by-puuid/mmr/ap/pc/p");
    }

    @Test
    void getRankIgnoresAccountNameOfAnotherPuuid() {
        body = """
                {"status":200,"data":{"account":{"name":"Other","tag":"KR1","puuid":"other"},
                 "current":{"tier":{"name":"Gold 2"},"rr":41}}}
                """;

        assertThat(client().getRank(ValorantRegion.KR, "p").riotId()).isNull();
    }

    @Test
    void rankAndMatchesNotFoundMeanNoRecord() {
        status = 404;

        assertThat(client().getRank(ValorantRegion.KR, "p")).isEqualTo(new ValorantRank(null, null, null, null));
        assertThat(client().getRecentCompetitiveMatches(ValorantRegion.KR, "p")).isEmpty();
    }

    @Test
    void matchesKeepOnlyCompletedCompetitiveGamesOfThePlayer() {
        body = """
                {"status":200,"data":[
                  %s,
                  %s,
                  %s,
                  %s
                ]}
                """.formatted(
                match("competitive", true, "me"),
                match("unrated", true, "me"),
                match("competitive", false, "me"),
                match("competitive", true, "someone-else"));

        List<ValorantMatchStats> matches = client().getRecentCompetitiveMatches(ValorantRegion.KR, "ME");

        assertThat(matches).containsExactly(new ValorantMatchStats(20, 10, 5, 4800, 24, 8, 20, 2, true));
        assertThat(uris.get(0)).isEqualTo("/valorant/v4/by-puuid/matches/kr/pc/ME?mode=competitive&size=10");
    }

    @Test
    void matchesSincePagesUntilAnOlderMatchAndReadsDetails() {
        Instant since = Instant.parse("2026-10-26T15:00:00Z");
        responses.add(new String[] {"200", page(10, "a", "2026-11-05T10:00:00Z"), null});
        // 두 번째 페이지: 앞 페이지와 겹친 1경기, 새 1경기, since 이전 8경기.
        responses.add(new String[] {"200", """
                {"status":200,"data":[%s,%s,%s]}
                """.formatted(detailedMatch("a0", "2026-11-05T10:00:00Z"), detailedMatch("b0", "2026-10-27T00:00:00Z"),
                detailedMatches(8, "old", "2026-10-26T14:59:59Z")), null});

        var matches = client().getCompetitiveMatchesSince(ValorantRegion.KR, "me", since);

        assertThat(matches).hasSize(11);
        assertThat(matches.get(0)).satisfies(match -> {
            assertThat(match.matchId()).isEqualTo("a0");
            assertThat(match.startedAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
            assertThat(match.map()).isEqualTo("Bind");
            assertThat(match.agent()).isEqualTo("Reyna");
            assertThat(match.tierId()).isEqualTo(12);
            assertThat(match.tierLabel()).isEqualTo("Gold 1");
            assertThat(match.stats().kills()).isEqualTo(20);
            assertThat(match.stats().won()).isTrue();
        });
        assertThat(matches.get(10).matchId()).isEqualTo("b0");
        assertThat(uris).containsExactly(
                "/valorant/v4/by-puuid/matches/kr/pc/me?mode=competitive&size=10&start=0",
                "/valorant/v4/by-puuid/matches/kr/pc/me?mode=competitive&size=10&start=10");
    }

    @Test
    void matchesSinceStopsAtShortPage() {
        responses.add(new String[] {"200", page(3, "a", "2026-11-05T10:00:00Z"), null});
        assertThat(client().getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH)).hasSize(3);
        assertThat(uris).hasSize(1);

    }

    @Test
    void matchesSinceCollectsBeyondOneHundredMatchesBeforeReachingBoundary() {
        for (int page = 0; page < 11; page++) {
            responses.add(new String[] {"200", page(10, "page" + page + "-", "2026-11-05T10:00:00Z"), null});
        }
        responses.add(new String[] {"200", page(10, "old", "2026-10-25T10:00:00Z"), null});

        var matches = client().getCompetitiveMatchesSince(
                ValorantRegion.KR, "me", Instant.parse("2026-10-26T00:00:00Z"));

        assertThat(matches).hasSize(110);
        assertThat(matches.get(109).matchId()).isEqualTo("page10-9");
        assertThat(uris).hasSize(12);
        assertThat(uris.get(11)).endsWith("&start=110");
    }

    @Test
    void matchesSinceFailsOnRepeatedFullPageInsteadOfReturningPartialHistory() {
        body = page(10, "same", "2026-11-05T10:00:00Z");

        assertStatus(() -> client().getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH),
                HttpStatus.BAD_GATEWAY);
        assertThat(uris).hasSize(2);
    }

    @Test
    void matchesSinceFailsWhenTimeCannotBeChecked() {
        body = page(1, "a", "invalid-time");

        assertStatus(() -> client().getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH),
                HttpStatus.BAD_GATEWAY);
    }

    @Test
    void matchesSinceIncludesExactlyOneHundredMatchesAndChecksTheNextPage() {
        for (int page = 0; page < 10; page++) {
            responses.add(new String[] {"200", page(10, "page" + page + "-", "2026-11-05T10:00:00Z"), null});
        }
        responses.add(new String[] {"200", page(0, "end", "2026-11-05T10:00:00Z"), null});

        assertThat(client().getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH)).hasSize(100);
        assertThat(uris).hasSize(11);
    }

    @Test
    void matchesSinceWaitsRetryAfterOn429ThenGivesUp() {
        List<Long> pauses = new ArrayList<>();
        HenrikValorantClient client = client();
        client.pause = pauses::add;
        responses.add(new String[] {"429", "{}", "7"});
        responses.add(new String[] {"200", page(1, "a", "2026-11-05T10:00:00Z"), null});

        assertThat(client.getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH)).hasSize(1);
        assertThat(pauses).containsExactly(7000L);

        pauses.clear();
        status = 429;
        assertStatus(() -> client.getCompetitiveMatchesSince(ValorantRegion.KR, "me", Instant.EPOCH),
                HttpStatus.TOO_MANY_REQUESTS);
        // Retry-After가 없으면 60초, 두 번 다시 시도한 뒤 포기한다.
        assertThat(pauses).containsExactly(60000L, 60000L);
    }

    private String page(int count, String idPrefix, String startedAt) {
        return "{\"status\":200,\"data\":[" + detailedMatches(count, idPrefix, startedAt) + "]}";
    }

    @Test
    void competitiveHistoryReadsMatchIdsTimesAndMapsAndDeduplicates() {
        body = """
                {"status":200,"data":{"history":[
                  {"match_id":"missing","date":"2026-11-02T15:27:02.659Z","map":{"name":"Lotus"}},
                  {"match_id":"missing","date":"2026-11-02T15:27:02.659Z","map":{"name":"Lotus"}}
                ]}}
                """;

        var history = client().getCompetitiveMatchHistory(ValorantRegion.AP, "me");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).matchId()).isEqualTo("missing");
        assertThat(history.get(0).startedAt()).isEqualTo(Instant.parse("2026-11-02T15:27:02.659Z"));
        assertThat(history.get(0).map()).isEqualTo("Lotus");
        assertThat(uris).containsExactly("/valorant/v2/by-puuid/mmr-history/ap/pc/me");
    }

    @Test
    void matchDetailDistinguishesMissingMatchFromAvailableDetail() {
        responses.add(new String[] {"404", "{\"errors\":[{\"code\":26,\"message\":\"Match not found\"}]}", null});
        responses.add(new String[] {"200", "{\"data\":" + detailedMatch("m1", "2026-11-03T00:00:00Z") + "}", null});
        HenrikValorantClient client = client();

        assertThat(client.getMatchDetail(ValorantRegion.AP, "me", "missing")).isEmpty();
        assertThat(client.getMatchDetail(ValorantRegion.AP, "me", "m1")).get()
                .satisfies(match -> assertThat(match.stats().kills()).isEqualTo(20));
        assertThat(uris).containsExactly("/valorant/v4/match/ap/missing", "/valorant/v4/match/ap/m1");
    }

    @Test
    void historyRejectsUnusableDatesAndDetailRejectsAnotherMatch() {
        body = "{\"data\":{\"history\":[{\"match_id\":\"m1\",\"date\":\"invalid\"}]}}";
        assertStatus(() -> client().getCompetitiveMatchHistory(ValorantRegion.AP, "me"), HttpStatus.BAD_GATEWAY);
        body = "{\"data\":" + detailedMatch("different", "2026-11-03T00:00:00Z") + "}";
        assertStatus(() -> client().getMatchDetail(ValorantRegion.AP, "me", "m1"), HttpStatus.BAD_GATEWAY);
    }

    private String detailedMatches(int count, String idPrefix, String startedAt) {
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            matches.add(detailedMatch(idPrefix + i, startedAt));
        }
        return String.join(",", matches);
    }

    private String detailedMatch(String matchId, String startedAt) {
        return """
                {"metadata":{"match_id":"%s","started_at":"%s","map":{"name":"Bind"},
                   "queue":{"id":"competitive"},"is_completed":true},
                 "players":[{"puuid":"me","team_id":"Red","agent":{"name":"Reyna"},"tier":{"id":12,"name":"Gold 1"},
                   "stats":{"kills":20,"deaths":10,"assists":5,"score":4800,
                   "headshots":8,"bodyshots":20,"legshots":2}}],
                 "teams":[{"team_id":"Red","won":true,"rounds":{"won":13,"lost":11}}]}
                """.formatted(matchId, startedAt);
    }

    private String match(String queue, boolean completed, String puuid) {
        return """
                {"metadata":{"queue":{"id":"%s"},"is_completed":%s},
                 "players":[{"puuid":"%s","team_id":"Red","stats":{"kills":20,"deaths":10,"assists":5,"score":4800,
                   "headshots":8,"bodyshots":20,"legshots":2}}],
                 "teams":[{"team_id":"Blue","won":false,"rounds":{"won":11,"lost":13}},
                          {"team_id":"Red","won":true,"rounds":{"won":13,"lost":11}}]}
                """.formatted(queue, completed, puuid);
    }

    private HenrikValorantClient client() {
        return new HenrikValorantClient("test-key", baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(expected));
    }

    private void handle(HttpExchange exchange) throws IOException {
        uris.add(exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String[] queued = responses.poll();
        byte[] bytes = (queued == null ? body : queued[1]).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (queued != null && queued[2] != null) {
            exchange.getResponseHeaders().add("Retry-After", queued[2]);
        }
        exchange.sendResponseHeaders(queued == null ? status : Integer.parseInt(queued[0]), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } catch (IOException ignored) {
            // The client may close the connection after a read timeout.
        }
    }
}
