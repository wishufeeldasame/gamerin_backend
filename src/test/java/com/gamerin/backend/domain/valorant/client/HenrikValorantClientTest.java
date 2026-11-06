package com.gamerin.backend.domain.valorant.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
                {"status":200,"data":{"current":{"tier":{"id":13,"name":"Gold 2"},"rr":41},
                 "peak":{"tier":{"id":16,"name":"Platinum 2"}}}}
                """;

        assertThat(client().getRank(ValorantRegion.AP, "p"))
                .isEqualTo(new ValorantRank("Gold 2", "Platinum 2", 41));
        assertThat(uris.get(0)).isEqualTo("/valorant/v3/by-puuid/mmr/ap/pc/p");
    }

    @Test
    void rankAndMatchesNotFoundMeanNoRecord() {
        status = 404;

        assertThat(client().getRank(ValorantRegion.KR, "p")).isEqualTo(new ValorantRank(null, null, null));
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } catch (IOException ignored) {
            // The client may close the connection after a read timeout.
        }
    }
}
