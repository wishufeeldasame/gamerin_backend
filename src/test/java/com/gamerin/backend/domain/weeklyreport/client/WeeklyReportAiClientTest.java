package com.gamerin.backend.domain.weeklyreport.client;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportCollectionIssue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyReportAiClientTest {

    private static final WeeklyStats STATS = new WeeklyStats(
            new WeekStats(12, 6, 50.0, 1.1, 24.6, 210, "골드 1", List.of(), List.of()), null, null);
    private static final WeeklyInsight INSIGHT = new WeeklyInsight(new WeeklyInsight.Week(
            new WeeklyInsight.Stats(12, 6, 300, 200, 180, 1.1, 210.0, 24.6, 12, null),
            List.of(), List.of(), List.of(), null, null, List.of(), 0), null);
    private static final String VALID = "{\"headline\":\"제목\",\"summary\":\"요약입니다.\",\"focus\":\"점검해 보세요.\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Deque<String[]> responses = new ArrayDeque<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private HttpServer server;

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
    void neverSendsDiagnosticMatchIdsDatesOrErrorsToAi() {
        responses.add(completion(200, VALID));
        WeeklyStats stats = new WeeklyStats(STATS.current(), STATS.previous(), STATS.tierChange(), List.of(
                new WeeklyReportCollectionIssue("private-match-id", Instant.parse("2026-11-02T15:27:00Z"),
                        "Lotus", 404, "diagnostic-only-error")));

        assertThat(client("factchat", "key", "model").analyze(stats, null)).isPresent();

        assertThat(requests.get(0).path("messages").get(1).path("content").asText())
                .doesNotContain("private-match-id", "2026-11-02", "404", "diagnostic-only-error")
                .contains("\"collectionIssues\":1");
    }

    @Test
    void missingKeyOrModelSkipsRequest() {
        assertThat(client("factchat", "", "model").analyze(STATS, INSIGHT)).isEmpty();
        assertThat(client("factchat", "key", " ").analyze(STATS, INSIGHT)).isEmpty();
        assertThat(client("factchat", "", "model").model()).isNull();
        assertThat(requests).isEmpty();
    }

    @Test
    void sendsOnlyAggregatedNumbersToSelectedProvider() {
        responses.add(completion(200, "```json\n" + VALID + "\n```"));

        WeeklyReportAiClient client = client("nvidia", "key", "model");

        assertThat(client.analyze(STATS, INSIGHT)).contains(new WeeklyAnalysis("제목", "요약입니다.", "점검해 보세요."));
        assertThat(client.model()).isEqualTo("nvidia-model");
        assertThat(authorizations).containsExactly("Bearer nvidia-key");
        JsonNode request = requests.get(0);
        assertThat(request.path("model").asText()).isEqualTo("nvidia-model");
        assertThat(request.path("messages").path(0).path("role").asText()).isEqualTo("system");
        JsonNode input = readTree(request.path("messages").path(1).path("content").asText());
        assertThat(input.path("current").path("matches").asInt()).isEqualTo(12);
        assertThat(input.path("insight").path("current").path("overall").path("insightMatches").asInt()).isEqualTo(12);
        assertThat(input.path("collectionIssues").asInt()).isZero();
        // null 필드는 빠지고 식별정보 필드는 없다.
        assertThat(input.has("previous")).isFalse();
        assertThat(input.toString()).doesNotContain("puuid", "matchId", "startedAt", "nickname");
        assertThat(request.path("max_tokens").asInt()).isEqualTo(32000);
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("medium");
    }

    @Test
    void retriesOnceAfterInvalidResponseThenGivesUp() {
        responses.add(completion(200, "분석 결과입니다."));
        responses.add(completion(200, VALID));
        assertThat(client("factchat", "key", "model").analyze(STATS, INSIGHT)).isPresent();
        assertThat(requests).hasSize(2);

        requests.clear();
        responses.add(completion(500, ""));
        responses.add(completion(200, "{\"headline\":\"제목\"}"));
        responses.add(completion(200, VALID));
        assertThat(client("factchat", "key", "model").analyze(STATS, INSIGHT)).isEmpty();
        assertThat(requests).hasSize(2);
    }

    @Test
    void parseRejectsMissingBlankOrTooLongFields() {
        assertThat(WeeklyReportAiClient.parse(null, objectMapper)).isEmpty();
        assertThat(WeeklyReportAiClient.parse("{not json}", objectMapper)).isEmpty();
        assertThat(WeeklyReportAiClient.parse(
                "{\"headline\":\" \",\"summary\":\"a\",\"focus\":\"b\"}", objectMapper)).isEmpty();
        assertThat(WeeklyReportAiClient.parse(
                "{\"headline\":1,\"summary\":\"a\",\"focus\":\"b\"}", objectMapper)).isEmpty();
        assertThat(WeeklyReportAiClient.parse(
                "{\"headline\":\"" + "가".repeat(81) + "\",\"summary\":\"a\",\"focus\":\"b\"}", objectMapper)).isEmpty();
        assertThat(WeeklyReportAiClient.parse(
                "{\"headline\":\"제목\",\"summary\":\"" + "가".repeat(1100) + "\",\"focus\":\"b\"}", objectMapper))
                .isPresent();
        assertThat(WeeklyReportAiClient.parse(
                "{\"headline\":\"제목\",\"summary\":\"첫 문장입니다.\\n\\n둘째 문장입니다.\",\"focus\":\"b\"}",
                objectMapper)).map(WeeklyAnalysis::summary).contains("첫 문장입니다. 둘째 문장입니다.");
        assertThat(WeeklyReportAiClient.parse("설명 " + VALID + " 끝", objectMapper)).isPresent();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WeeklyReportAiClient client(String provider, String key, String model) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
        return new WeeklyReportAiClient(provider,
                url, key.isBlank() ? key : "factchat-" + key, model.isBlank() ? model : "factchat-" + model,
                url, key.isBlank() ? key : "nvidia-" + key, model.isBlank() ? model : "nvidia-" + model,
                Duration.ofSeconds(1), Duration.ofSeconds(2), "medium", objectMapper);
    }

    private String[] completion(int status, String content) {
        return new String[] {String.valueOf(status), objectMapper.createObjectNode()
                .set("choices", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                        .set("message", objectMapper.createObjectNode().put("content", content)))).toString()};
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        requests.add(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
        String[] response = responses.poll();
        byte[] bytes = response[1].getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(Integer.parseInt(response[0]), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
