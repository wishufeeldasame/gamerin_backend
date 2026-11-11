package com.gamerin.backend.domain.valorant.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.valorant.model.MatchInsight;
import com.gamerin.backend.domain.valorant.model.MatchInsight.AbilityCasts;
import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;
import org.junit.jupiter.api.Test;

/** 나=A1(Blue), 아군 A2·A3, 상대 E1·E2(Red). */
class MatchInsightParserTest {

    private static final String PLAYERS = """
            [{"puuid":"A1","team_id":"Blue","stats":{"damage":{"dealt":300}},
              "ability_casts":{"grenade":1,"ability1":2,"ability2":3,"ultimate":4}},
             {"puuid":"A2","team_id":"Blue"},{"puuid":"E1","team_id":"Red"}]""";

    @Test
    void firstEngagementSkipsTeamKillSuicideAndTies() {
        MatchInsight insight = parse(PLAYERS,
                round(0, "Blue", "Attacker", null, "[]") + "," + round(1, "Blue", "Attacker", null, "[]") + ","
                        + round(2, "Red", "Attacker", null, "[]"),
                kill(0, 50, "E1", "Red", "E1", "Red") + "," + kill(0, 100, "A2", "Blue", "A3", "Blue") + ","
                        + kill(0, 1000, "A1", "Blue", "E1", "Red") + ","
                        + kill(1, 500, "E1", "Red", "A1", "Blue") + "," + kill(1, 500, "A2", "Blue", "E2", "Red") + ","
                        + kill(2, 300, "E2", "Red", "A1", "Blue"));

        assertThat(insight.firstKills()).isEqualTo(1);
        assertThat(insight.firstKillRoundWins()).isEqualTo(1);
        assertThat(insight.firstDeaths()).isEqualTo(1);
        assertThat(insight.firstDeathRoundWins()).isZero();
        assertThat(insight.damageDealt()).isEqualTo(300);
        assertThat(insight.abilityCasts()).isEqualTo(new AbilityCasts(1, 2, 3, 4));
    }

    @Test
    void sideFollowsWinningTeamRole() {
        MatchInsight insight = parse(PLAYERS,
                round(0, "Blue", "Attacker", null, "[]") + "," + round(1, "Red", "Attacker", null, "[]") + ","
                        + round(2, "Blue", "", null, "[]"),
                kill(0, 1000, "A1", "Blue", "E1", "Red") + "," + kill(1, 1000, "E1", "Red", "A1", "Blue"));

        assertThat(insight.attack()).isEqualTo(new RoundGroup(1, 1, 1, 0));
        assertThat(insight.defense()).isEqualTo(new RoundGroup(1, 0, 0, 1));
    }

    @Test
    void postPlantCountsOnlyKillsAfterPlant() {
        MatchInsight insight = parse(PLAYERS,
                round(0, "Blue", "Attacker", plant("Blue", 30000), "[]") + ","
                        + round(1, "Blue", "Defender", plant("Red", 40000), "[]"),
                kill(0, 29000, "A1", "Blue", "E1", "Red") + "," + kill(0, 31000, "A1", "Blue", "E2", "Red") + ","
                        + kill(0, 32000, "E3", "Red", "A1", "Blue"));

        assertThat(insight.postPlantAttack()).isEqualTo(new RoundGroup(1, 1, 1, 1));
        assertThat(insight.postPlantDefense()).isEqualTo(new RoundGroup(1, 1, 0, 0));
    }

    @Test
    void tradeWindowIncludesFiveSecondsExactly() {
        MatchInsight insight = parse(PLAYERS,
                round(0, "Red", "Attacker", null, "[]") + "," + round(1, "Red", "Attacker", null, "[]") + ","
                        + round(2, "Blue", "Attacker", null, "[]") + "," + round(3, "Blue", "Attacker", null, "[]"),
                kill(0, 1000, "E1", "Red", "A1", "Blue") + "," + kill(0, 6000, "A2", "Blue", "E1", "Red") + ","
                        + kill(1, 1000, "E1", "Red", "A1", "Blue") + "," + kill(1, 6001, "A2", "Blue", "E1", "Red") + ","
                        + kill(2, 1000, "E2", "Red", "A2", "Blue") + "," + kill(2, 6000, "A1", "Blue", "E2", "Red") + ","
                        + kill(3, 1000, "E2", "Red", "A2", "Blue") + "," + kill(3, 6001, "A1", "Blue", "E2", "Red"));

        assertThat(insight.tradedDeaths()).isEqualTo(1);
        assertThat(insight.tradeKills()).isEqualTo(1);
    }

    @Test
    void economyGapBoundaryIsThousand() {
        MatchInsight insight = parse(PLAYERS,
                round(0, "Blue", "Attacker", null, loadouts("3000", "4000")) + ","
                        + round(1, "Red", "Attacker", null, loadouts("4000", "3000")) + ","
                        + round(2, "Blue", "Attacker", null, loadouts("3001", "4000")) + ","
                        + round(3, "Blue", "Attacker", null, loadouts("null", "4000")),
                "");

        assertThat(insight.economyBehind()).isEqualTo(new RoundGroup(1, 1, 0, 0));
        assertThat(insight.economyAhead()).isEqualTo(new RoundGroup(1, 0, 0, 0));
        assertThat(insight.economyEven()).isEqualTo(new RoundGroup(1, 1, 0, 0));
    }

    @Test
    void missingValuesAreNullNotZero() {
        assertThat(parse("[{\"puuid\":\"X\",\"team_id\":\"Blue\"}]", "", "")).isNull();

        MatchInsight insight = parse("[{\"puuid\":\"A1\",\"team_id\":\"Blue\",\"ability_casts\":{\"grenade\":null}}]",
                round(0, "Blue", "Attacker", null, """
                        [{"player":{"puuid":"A2","team":"Blue"},"was_afk":true,"economy":{}}]"""), "");
        assertThat(insight.damageDealt()).isNull();
        assertThat(insight.abilityCasts()).isNull();
        assertThat(insight.allyAfk()).isTrue();
        assertThat(insight.economyEven()).isEqualTo(RoundGroup.EMPTY);
    }

    private static MatchInsight parse(String players, String rounds, String kills) {
        try {
            JsonNode match = new ObjectMapper().readTree(
                    "{\"players\":" + players + ",\"rounds\":[" + rounds + "],\"kills\":[" + kills + "]}");
            return MatchInsightParser.parse(match, "A1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String round(int id, String winner, String role, String plant, String stats) {
        return "{\"id\":%d,\"winning_team\":\"%s\",\"winning_team_role\":\"%s\",\"plant\":%s,\"stats\":%s}"
                .formatted(id, winner, role, plant, stats);
    }

    private static String plant(String team, long at) {
        return "{\"round_time_in_ms\":%d,\"player\":{\"team\":\"%s\"}}".formatted(at, team);
    }

    private static String kill(int round, long at, String killer, String killerTeam, String victim,
            String victimTeam) {
        return ("{\"round\":%d,\"time_in_round_in_ms\":%d,\"killer\":{\"puuid\":\"%s\",\"team\":\"%s\"},"
                + "\"victim\":{\"puuid\":\"%s\",\"team\":\"%s\"}}")
                .formatted(round, at, killer, killerTeam, victim, victimTeam);
    }

    /** 아군 A1·A2 는 ours, 상대 E1 은 theirs. */
    private static String loadouts(String ours, String theirs) {
        String row = "{\"player\":{\"puuid\":\"%s\",\"team\":\"%s\"},\"economy\":{\"loadout_value\":%s}}";
        return "[" + row.formatted("A1", "Blue", ours) + "," + row.formatted("A2", "Blue", ours) + ","
                + row.formatted("E1", "Red", theirs) + "]";
    }
}
