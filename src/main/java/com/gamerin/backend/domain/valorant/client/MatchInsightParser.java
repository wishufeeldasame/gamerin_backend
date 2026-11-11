package com.gamerin.backend.domain.valorant.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamerin.backend.domain.valorant.model.MatchInsight;
import com.gamerin.backend.domain.valorant.model.MatchInsight.AbilityCasts;
import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;

/** v4 경기 응답(목록·단건 공통 형태)에서 대상 플레이어의 {@link MatchInsight}를 계산한다. 추가 호출은 없다. */
final class MatchInsightParser {

    static final long TRADE_WINDOW_MS = 5000;
    static final int ECONOMY_GAP = 1000;

    private MatchInsightParser() {
    }

    /** 대상 플레이어나 팀을 찾지 못하거나 rounds·kills 배열이 없거나 응답 형태가 예상과 다르면 null이다. */
    static MatchInsight parse(JsonNode match, String puuid) {
        try {
            return compute(match, puuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Kill(int round, long time, String killer, String killerTeam, String victim, String victimTeam) {
    }

    private static MatchInsight compute(JsonNode match, String puuid) {
        JsonNode player = null;
        for (JsonNode item : match.path("players")) {
            if (puuid.equalsIgnoreCase(item.path("puuid").asText(""))) {
                player = item;
            }
        }
        if (player == null) {
            return null;
        }
        String myTeam = player.path("team_id").asText("");
        if (myTeam.isEmpty()) {
            return null;
        }

        // 상세 배열이 없는 응답을 첫 교전·킬 교환 0건으로 세지 않도록 근거 없음(null)으로 둔다.
        if (!match.path("kills").isArray() || !match.path("rounds").isArray() || match.path("rounds").isEmpty()) {
            return null;
        }

        List<Kill> kills = new ArrayList<>();
        for (JsonNode k : match.path("kills")) {
            String killerTeam = k.path("killer").path("team").asText("");
            String victimTeam = k.path("victim").path("team").asText("");
            // 자살·팀킬은 모든 지표에서 뺀다.
            if (killerTeam.isEmpty() || killerTeam.equalsIgnoreCase(victimTeam)
                    || !k.path("round").isInt() || !k.path("time_in_round_in_ms").isNumber()) {
                continue;
            }
            kills.add(new Kill(k.path("round").asInt(), k.path("time_in_round_in_ms").asLong(),
                    k.path("killer").path("puuid").asText(""), killerTeam,
                    k.path("victim").path("puuid").asText(""), victimTeam));
        }

        int firstKills = 0, firstDeaths = 0, firstKillWins = 0, firstDeathWins = 0, traded = 0, tradeKills = 0;
        int myAfk = 0;
        boolean allyAfk = false, enemyAfk = false;
        RoundGroup attack = RoundGroup.EMPTY, defense = RoundGroup.EMPTY;
        RoundGroup plantAttack = RoundGroup.EMPTY, plantDefense = RoundGroup.EMPTY;
        RoundGroup behind = RoundGroup.EMPTY, even = RoundGroup.EMPTY, ahead = RoundGroup.EMPTY;

        for (JsonNode round : match.path("rounds")) {
            int id = round.path("id").asInt(-1);
            boolean won = myTeam.equalsIgnoreCase(round.path("winning_team").asText(""));
            List<Kill> roundKills = kills.stream().filter(k -> k.round == id).toList();
            int myKills = (int) roundKills.stream().filter(k -> puuid.equalsIgnoreCase(k.killer)).count();
            int myDeaths = (int) roundKills.stream().filter(k -> puuid.equalsIgnoreCase(k.victim)).count();
            RoundGroup thisRound = new RoundGroup(1, won ? 1 : 0, myKills, myDeaths);

            // 첫 교전: 최소 시각이 동률이면 그 라운드는 세지 않는다.
            long first = roundKills.stream().mapToLong(Kill::time).min().orElse(-1);
            List<Kill> firsts = roundKills.stream().filter(k -> k.time == first).toList();
            if (firsts.size() == 1) {
                Kill k = firsts.get(0);
                if (puuid.equalsIgnoreCase(k.killer)) {
                    firstKills++;
                    firstKillWins += won ? 1 : 0;
                } else if (puuid.equalsIgnoreCase(k.victim)) {
                    firstDeaths++;
                    firstDeathWins += won ? 1 : 0;
                }
            }

            String role = round.path("winning_team_role").asText("");
            if ("Attacker".equals(role) || "Defender".equals(role)) {
                boolean attacking = won == "Attacker".equals(role);
                if (attacking) {
                    attack = attack.plus(thisRound);
                } else {
                    defense = defense.plus(thisRound);
                }
            }

            JsonNode plant = round.path("plant");
            String plantTeam = plant.path("player").path("team").asText("");
            if (plant.isObject() && !plantTeam.isEmpty() && plant.path("round_time_in_ms").isNumber()) {
                long plantedAt = plant.path("round_time_in_ms").asLong();
                List<Kill> after = roundKills.stream().filter(k -> k.time > plantedAt).toList();
                RoundGroup postPlant = new RoundGroup(1, won ? 1 : 0,
                        (int) after.stream().filter(k -> puuid.equalsIgnoreCase(k.killer)).count(),
                        (int) after.stream().filter(k -> puuid.equalsIgnoreCase(k.victim)).count());
                if (myTeam.equalsIgnoreCase(plantTeam)) {
                    plantAttack = plantAttack.plus(postPlant);
                } else {
                    plantDefense = plantDefense.plus(postPlant);
                }
            }

            for (Kill death : roundKills) {
                if (puuid.equalsIgnoreCase(death.victim) && roundKills.stream().anyMatch(f ->
                        f != death && myTeam.equalsIgnoreCase(f.killerTeam)
                                && f.victim.equalsIgnoreCase(death.killer) && within(death, f))) {
                    traded++;
                }
                if (puuid.equalsIgnoreCase(death.killer) && roundKills.stream().anyMatch(e ->
                        e != death && myTeam.equalsIgnoreCase(e.victimTeam) && !puuid.equalsIgnoreCase(e.victim)
                                && e.killer.equalsIgnoreCase(death.victim) && within(e, death))) {
                    tradeKills++;
                }
            }

            double ours = 0, theirs = 0;
            int ourCount = 0, theirCount = 0;
            boolean economyKnown = true;
            for (JsonNode stat : round.path("stats")) {
                String team = stat.path("player").path("team").asText("");
                boolean mine = myTeam.equalsIgnoreCase(team);
                boolean me = puuid.equalsIgnoreCase(stat.path("player").path("puuid").asText(""));
                if (stat.path("was_afk").asBoolean(false)) {
                    if (me) {
                        myAfk++;
                    } else if (mine) {
                        allyAfk = true;
                    } else {
                        enemyAfk = true;
                    }
                }
                JsonNode loadout = stat.path("economy").path("loadout_value");
                if (!loadout.isNumber()) {
                    economyKnown = false;
                } else if (mine) {
                    ours += loadout.asDouble();
                    ourCount++;
                } else {
                    theirs += loadout.asDouble();
                    theirCount++;
                }
            }
            if (economyKnown && ourCount > 0 && theirCount > 0) {
                double gap = ours / ourCount - theirs / theirCount;
                if (gap <= -ECONOMY_GAP) {
                    behind = behind.plus(thisRound);
                } else if (gap >= ECONOMY_GAP) {
                    ahead = ahead.plus(thisRound);
                } else {
                    even = even.plus(thisRound);
                }
            }
        }

        JsonNode damage = player.path("stats").path("damage").path("dealt");
        return new MatchInsight(damage.isNumber() ? damage.asInt() : null,
                firstKills, firstDeaths, firstKillWins, firstDeathWins, attack, defense, plantAttack, plantDefense,
                traded, tradeKills, behind, even, ahead, abilityCasts(player.path("ability_casts")),
                allyAfk, enemyAfk, myAfk);
    }

    /** later 이벤트가 earlier 이후 창(5초, 경계 포함) 안에 있는지. */
    private static boolean within(Kill earlier, Kill later) {
        long gap = later.time - earlier.time;
        return gap >= 0 && gap <= TRADE_WINDOW_MS;
    }

    private static AbilityCasts abilityCasts(JsonNode casts) {
        JsonNode c = casts.path("grenade"), q = casts.path("ability1"), e = casts.path("ability2"),
                x = casts.path("ultimate");
        if (!c.isNumber() || !q.isNumber() || !e.isNumber() || !x.isNumber()) {
            return null;
        }
        return new AbilityCasts(c.asInt(), q.asInt(), e.asInt(), x.asInt());
    }
}
