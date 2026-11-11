package com.gamerin.backend.domain.weeklyreport.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.gamerin.backend.domain.valorant.model.MatchInsight;
import com.gamerin.backend.domain.valorant.model.MatchInsight.AbilityCasts;
import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.AgentAbility;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Evidence;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Group;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Stats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Week;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;

/** 경기 기록을 대상 주·비교 주로 나눠 AI 입력용 지표로 합산한다. 비율은 모두 합계 기반이다. 외부 호출 없음. */
public final class WeeklyInsightCalculator {

    private static final String UNKNOWN = "미확인";

    private WeeklyInsightCalculator() {
    }

    public static WeeklyInsight calculate(List<ValorantMatchRecord> records, WeeklyReportPeriod period) {
        return new WeeklyInsight(
                week(between(records, period.startInstant(), period.endExclusive())),
                week(between(records, period.previousStartInstant(), period.startInstant())));
    }

    private static List<ValorantMatchRecord> between(List<ValorantMatchRecord> records, OffsetDateTime from,
            OffsetDateTime to) {
        return records.stream()
                .filter(r -> !r.getStartedAt().isBefore(from) && r.getStartedAt().isBefore(to))
                .sorted(Comparator.comparing(ValorantMatchRecord::getStartedAt))
                .toList();
    }

    private static Week week(List<ValorantMatchRecord> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        return new Week(
                stats(matches),
                groups(matches, r -> name(r.getMapName())),
                groups(matches, r -> name(r.getAgentName())),
                groups(matches, r -> name(r.getMapName()) + " × " + name(r.getAgentName())),
                statsOrNull(matches, ValorantMatchRecord::isWon),
                statsOrNull(matches, r -> !r.isWon()),
                abilities(matches),
                (int) matches.stream().map(ValorantMatchRecord::getInsight)
                        .filter(i -> i != null && (i.allyAfk() || i.enemyAfk() || i.myAfkRounds() > 0)).count());
    }

    private static String name(String value) {
        return value == null ? UNKNOWN : value;
    }

    private static Stats statsOrNull(List<ValorantMatchRecord> matches, Predicate<ValorantMatchRecord> filter) {
        List<ValorantMatchRecord> selected = matches.stream().filter(filter).toList();
        return selected.isEmpty() ? null : stats(selected);
    }

    /** 경기 수 내림차순, 같으면 이름순. 개수 제한 없음. */
    private static List<Group> groups(List<ValorantMatchRecord> matches, Function<ValorantMatchRecord, String> key) {
        Map<String, List<ValorantMatchRecord>> grouped = new LinkedHashMap<>();
        matches.forEach(r -> grouped.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r));
        return grouped.entrySet().stream()
                .map(e -> new Group(e.getKey(), stats(e.getValue())))
                .sorted(Comparator.comparingInt((Group g) -> -g.stats().matches()).thenComparing(Group::label))
                .toList();
    }

    static Stats stats(List<ValorantMatchRecord> matches) {
        int wins = 0, rounds = 0, kills = 0, deaths = 0, score = 0, headshots = 0, shots = 0;
        int insightMatches = 0, damage = 0, damageRounds = 0;
        int firstKills = 0, firstDeaths = 0, firstKillWins = 0, firstDeathWins = 0, traded = 0, tradeKills = 0;
        RoundGroup attack = RoundGroup.EMPTY, defense = RoundGroup.EMPTY;
        RoundGroup plantAttack = RoundGroup.EMPTY, plantDefense = RoundGroup.EMPTY;
        RoundGroup behind = RoundGroup.EMPTY, even = RoundGroup.EMPTY, ahead = RoundGroup.EMPTY;
        for (ValorantMatchRecord r : matches) {
            wins += r.isWon() ? 1 : 0;
            rounds += r.getRounds();
            kills += r.getKills();
            deaths += r.getDeaths();
            score += r.getScore();
            headshots += r.getHeadshots();
            shots += r.getHeadshots() + r.getBodyshots() + r.getLegshots();
            MatchInsight i = r.getInsight();
            if (i == null) {
                continue;
            }
            insightMatches++;
            if (i.damageDealt() != null) {
                damage += i.damageDealt();
                damageRounds += r.getRounds();
            }
            firstKills += i.firstKills();
            firstDeaths += i.firstDeaths();
            firstKillWins += i.firstKillRoundWins();
            firstDeathWins += i.firstDeathRoundWins();
            traded += i.tradedDeaths();
            tradeKills += i.tradeKills();
            attack = attack.plus(i.attack());
            defense = defense.plus(i.defense());
            plantAttack = plantAttack.plus(i.postPlantAttack());
            plantDefense = plantDefense.plus(i.postPlantDefense());
            behind = behind.plus(i.economyBehind());
            even = even.plus(i.economyEven());
            ahead = ahead.plus(i.economyAhead());
        }
        Evidence evidence = insightMatches == 0 ? null : new Evidence(
                damageRounds == 0 ? null : round1((double) damage / damageRounds),
                firstKills, firstDeaths, firstKillWins, firstDeathWins, attack, defense, plantAttack, plantDefense,
                traded, tradeKills, behind, even, ahead);
        return new Stats(matches.size(), wins, rounds, kills, deaths,
                deaths == 0 ? null : Math.round((double) kills / deaths * 100) / 100.0,
                rounds == 0 ? null : round1((double) score / rounds),
                shots == 0 ? null : round1(headshots * 100.0 / shots),
                insightMatches, evidence);
    }

    private static List<AgentAbility> abilities(List<ValorantMatchRecord> matches) {
        Map<String, int[]> sums = new LinkedHashMap<>(); // matches, rounds, C, Q, E, X
        for (ValorantMatchRecord r : matches) {
            AbilityCasts casts = r.getInsight() == null ? null : r.getInsight().abilityCasts();
            if (casts == null || r.getRounds() == 0) {
                continue;
            }
            int[] s = sums.computeIfAbsent(name(r.getAgentName()), k -> new int[6]);
            s[0]++;
            s[1] += r.getRounds();
            s[2] += casts.grenade();
            s[3] += casts.ability1();
            s[4] += casts.ability2();
            s[5] += casts.ultimate();
        }
        return sums.entrySet().stream()
                .map(e -> {
                    int[] s = e.getValue();
                    return new AgentAbility(e.getKey(), s[0], s[1], perRound(s[2], s[1]), perRound(s[3], s[1]),
                            perRound(s[4], s[1]), perRound(s[5], s[1]));
                })
                .sorted(Comparator.comparingInt((AgentAbility a) -> -a.matches()).thenComparing(AgentAbility::agent))
                .toList();
    }

    private static double perRound(int casts, int rounds) {
        return Math.round(casts * 100.0 / rounds) / 100.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
