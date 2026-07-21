package com.gamerin.backend.domain.r6.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class R6DataStatsClientTest {

    private HttpServer server;
    private Function<Map<String, String>, StubResponse> responder;
    private final List<RecordedRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/stats", this::handleRequest);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void findProfileReturns503WhenRequiredConfigurationIsMissing() {
        R6DataStatsClient client = new R6DataStatsClient("", "");

        assertThatThrownBy(() -> client.findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(requests).isEmpty();
    }

    @Test
    void findProfileMapsCurrentPcRankedStatsAndUsesR6DataContract() {
        responder = query -> switch (query.get("type")) {
            case "fullStats" -> StubResponse.ok(fullStatsResponse());
            case "seasonalStats" -> StubResponse.ok(seasonalStatsResponse());
            default -> new StubResponse(400, "{}");
        };

        R6Profile profile = client().findProfile("Player + One");

        assertThat(profile.playerName()).isEqualTo("CanonicalPlayer");
        assertThat(profile.accountId()).isEqualTo("account-41");
        assertThat(profile.summary().tierLabel()).isEqualTo("EMERALD II");
        assertThat(profile.summary().kd()).isEqualTo(1.05);
        assertThat(profile.summary().matches()).isEqualTo(117);
        assertThat(profile.summary().winRate()).isCloseTo(55.652173913, within(0.000000001));

        assertThat(requests).hasSize(2);
        RecordedRequest fullStatsRequest = requests.get(0);
        assertThat(fullStatsRequest.apiKey()).isEqualTo("test-api-key");
        assertThat(fullStatsRequest.query())
                .containsEntry("type", "fullStats")
                .containsEntry("nameOnPlatform", "Player + One")
                .containsEntry("platformType", "uplay")
                .containsEntry("modes", "all");

        RecordedRequest seasonalStatsRequest = requests.get(1);
        assertThat(seasonalStatsRequest.apiKey()).isEqualTo("test-api-key");
        assertThat(seasonalStatsRequest.query())
                .containsEntry("type", "seasonalStats")
                .containsEntry("nameOnPlatform", "Player + One")
                .containsEntry("platformType", "uplay")
                .doesNotContainKey("modes");
    }

    @Test
    void findProfileAllowsAccountWithoutCurrentRankedStats() {
        responder = query -> StubResponse.ok(unrankedFullStatsResponse());

        R6Profile profile = client().findProfile("UnrankedPlayer");

        assertThat(profile.accountId()).isEqualTo("unranked-account");
        assertThat(profile.summary().tierLabel()).isNull();
        assertThat(profile.summary().kd()).isNull();
        assertThat(profile.summary().winRate()).isNull();
        assertThat(profile.summary().matches()).isNull();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).query()).containsEntry("type", "fullStats");
    }

    @Test
    void findProfileFallsBackToAggregatedQuickplayWhenCurrentRankedStatsAreEmpty() {
        responder = query -> StubResponse.ok(normalFallbackFullStatsResponse());

        R6Profile profile = client().findProfile("NormalPlayer");

        assertThat(profile.accountId()).isEqualTo("normal-account");
        assertThat(profile.summary().tierLabel()).isNull();
        assertThat(profile.summary().kd()).isEqualTo(1.41);
        assertThat(profile.summary().winRate()).isEqualTo(50.0);
        assertThat(profile.summary().matches()).isEqualTo(50);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).query())
                .containsEntry("type", "fullStats")
                .containsEntry("modes", "all");
    }

    @Test
    void findProfileUsesLargestNormalPlaylistWhenQuickplayAggregateIsMissing() {
        responder = query -> StubResponse.ok(normalAliasOnlyFullStatsResponse());

        R6Profile profile = client().findProfile("NormalPlayer");

        assertThat(profile.summary().tierLabel()).isNull();
        assertThat(profile.summary().kd()).isEqualTo(1.48);
        assertThat(profile.summary().winRate()).isCloseTo(53.658536585, within(0.000000001));
        assertThat(profile.summary().matches()).isEqualTo(42);
        assertThat(requests).hasSize(1);
    }

    @Test
    void getSummaryReturns502ForMissingProfileReference() {
        assertThatThrownBy(() -> client().getSummary(new R6ProfileRef(" ", "account-1")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(requests).isEmpty();
    }

    @Test
    void getSummaryRejectsPlayerNameReassignedToDifferentAccount() {
        responder = query -> StubResponse.ok(normalFallbackFullStatsResponse());

        assertThatThrownBy(() -> client().getSummary(new R6ProfileRef("NormalPlayer", "original-account")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());

        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
            "401, 503",
            "403, 503",
            "404, 404",
            "429, 429",
            "500, 502",
            "502, 502"
    })
    void findProfileMapsExternalHttpErrors(int externalStatus, int expectedStatus) {
        responder = query -> new StubResponse(externalStatus, "{\"error\":\"external failure\"}");

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(expectedStatus);
    }

    @Test
    void findProfileReturns502ForMissingAccountIdentifier() {
        responder = query -> StubResponse.ok("""
                {
                  "data": {
                    "platformInfo": {
                      "platformUserHandle": "R6Player"
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
    }

    @Test
    void findProfileReturns502ForMalformedRankedNumericValue() {
        responder = query -> StubResponse.ok(fullStatsResponse().replace(
                "\"kdRatio\": { \"value\": 1.05 }",
                "\"kdRatio\": { \"value\": \"invalid\" }"
        ));

        assertThatThrownBy(() -> client().findProfile("R6Player"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
    }

    private R6DataStatsClient client() {
        return new R6DataStatsClient(
                "test-api-key",
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String apiKey = exchange.getRequestHeaders().getFirst("api-key");
        requests.add(new RecordedRequest(query, apiKey));

        StubResponse response = responder == null
                ? new StubResponse(500, "{}")
                : responder.apply(query);
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            query.put(key, value);
        }
        return query;
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private String fullStatsResponse() {
        return """
                {
                  "seasonNumber": 41,
                  "platform_families_full_profiles": [
                    {
                      "profile_id": "account-41",
                      "board_ids_full_profiles": [
                        {
                          "board_id": "ranked",
                          "full_profiles": [
                            {
                              "season_id": 40,
                              "profile": {
                                "wins": 999,
                                "losses": 1,
                                "abandon": 0
                              }
                            },
                            {
                              "season_id": 41,
                              "profile": {
                                "rank": 18,
                                "rank_points": 3300,
                                "wins": 64,
                                "losses": 51,
                                "abandon": 2
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "data": {
                    "platformInfo": {
                      "platformSlug": "ubi",
                      "platformUserId": "account-41",
                      "platformUserHandle": "CanonicalPlayer"
                    },
                    "metadata": {
                      "currentSeason": 41
                    },
                    "segments": [
                      {
                        "attributes": {
                          "season": 40,
                          "gamemode": "pvp_ranked",
                          "platform": "pc",
                          "sessionType": "ranked"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 999 },
                          "kdRatio": { "value": 9.99 },
                          "rankPoints": { "value": 9999 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 41,
                          "gamemode": "pvp_casual",
                          "platform": "pc",
                          "sessionType": "casual"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 888 },
                          "kdRatio": { "value": 8.88 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 41,
                          "gamemode": "pvp_ranked",
                          "platform": "console",
                          "sessionType": "ranked"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 777 },
                          "kdRatio": { "value": 7.77 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 41,
                          "gamemode": "pvp_ranked",
                          "platform": "pc",
                          "sessionType": "ranked"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 117 },
                          "kdRatio": { "value": 1.05 },
                          "rankPoints": { "value": 3300 }
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private String seasonalStatsResponse() {
        return """
                {
                  "data": {
                    "history": {
                      "data": [
                        [
                          "2026-07-16T12:00:00+00:00",
                          { "metadata": { "rank": "EMERALD II" }, "value": 3550 }
                        ],
                        [
                          "2026-07-10T12:00:00+00:00",
                          { "metadata": { "rank": "PLATINUM I" }, "value": 3300 }
                        ]
                      ]
                    }
                  }
                }
                """;
    }

    private String unrankedFullStatsResponse() {
        return """
                {
                  "platform_families_full_profiles": [
                    {
                      "board_ids_full_profiles": [
                        {
                          "board_id": "ranked",
                          "full_profiles": [
                            {
                              "season_id": 41,
                              "profile": { "wins": 0, "losses": 0, "abandon": 0 }
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "data": {
                    "platformInfo": {
                      "platformUserId": "unranked-account",
                      "platformUserHandle": "UnrankedPlayer"
                    },
                    "metadata": {
                      "currentSeason": 41
                    },
                    "segments": []
                  }
                }
                """;
    }

    private String normalFallbackFullStatsResponse() {
        return """
                {
                  "platform_families_full_profiles": [
                    {
                      "board_ids_full_profiles": [
                        {
                          "board_id": "ranked",
                          "full_profiles": [
                            {
                              "season_id": 42,
                              "profile": { "wins": 0, "losses": 0, "abandon": 0 }
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "data": {
                    "platformInfo": {
                      "platformUserId": "normal-account",
                      "platformUserHandle": "NormalPlayer"
                    },
                    "metadata": {
                      "currentSeason": 42
                    },
                    "segments": [
                      {
                        "attributes": {
                          "season": 42,
                          "gamemode": "pvp_ranked",
                          "platform": "pc",
                          "sessionType": "ranked"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 0 },
                          "kdRatio": { "value": 0.0 }
                        }
                      },
                      {
                        "attributes": {
                          "gamemode": "pvp_quickplay",
                          "platform": "pc",
                          "sessionType": "quickplay"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 50 },
                          "matchesWon": { "value": 24 },
                          "matchesLost": { "value": 24 },
                          "kdRatio": { "value": 1.41 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 40,
                          "gamemode": "pvp_casual",
                          "platform": "pc",
                          "sessionType": "quick-match"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 80 },
                          "matchesWon": { "value": 70 },
                          "matchesLost": { "value": 10 },
                          "kdRatio": { "value": 9.0 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 34,
                          "gamemode": "pvp_standard",
                          "platform": "pc",
                          "sessionType": "standard"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 70 },
                          "matchesWon": { "value": 60 },
                          "matchesLost": { "value": 10 },
                          "kdRatio": { "value": 8.0 }
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private String normalAliasOnlyFullStatsResponse() {
        return """
                {
                  "data": {
                    "platformInfo": {
                      "platformUserId": "normal-account",
                      "platformUserHandle": "NormalPlayer"
                    },
                    "metadata": {
                      "currentSeason": 42
                    },
                    "segments": [
                      {
                        "attributes": {
                          "season": 40,
                          "gamemode": "pvp_casual",
                          "platform": "pc",
                          "sessionType": "quick-match"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 8 },
                          "matchesWon": { "value": 2 },
                          "matchesLost": { "value": 6 },
                          "kdRatio": { "value": 1.0 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 34,
                          "gamemode": "pvp_standard",
                          "platform": "pc",
                          "sessionType": "standard"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 42 },
                          "matchesWon": { "value": 22 },
                          "matchesLost": { "value": 19 },
                          "kdRatio": { "value": 1.48 }
                        }
                      },
                      {
                        "attributes": {
                          "season": 35,
                          "gamemode": "pvp_unranked",
                          "platform": "pc",
                          "sessionType": "unranked"
                        },
                        "stats": {
                          "matchesPlayed": { "value": 10 },
                          "matchesWon": { "value": 5 },
                          "matchesLost": { "value": 5 },
                          "kdRatio": { "value": 1.2 }
                        }
                      }
                    ]
                  }
                }
                """;
    }

    private record RecordedRequest(Map<String, String> query, String apiKey) {
    }

    private record StubResponse(int status, String body) {

        private static StubResponse ok(String body) {
            return new StubResponse(200, body);
        }
    }
}
