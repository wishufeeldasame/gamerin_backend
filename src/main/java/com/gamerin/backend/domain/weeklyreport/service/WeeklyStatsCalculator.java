package com.gamerin.backend.domain.weeklyreport.service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats.Breakdown;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;

/** 경기 기록을 대상 주와 전주로 나눠 집계한다. 외부 호출과 AI를 쓰지 않는다. */
public final class WeeklyStatsCalculator {

    private static final int MAX_BREAKDOWN = 5;
    private static final Map<String, String> TIER_NAMES = Map.of(
            "Iron", "아이언", "Bronze", "브론즈", "Silver", "실버", "Gold", "골드", "Platinum", "플래티넘",
            "Diamond", "다이아몬드", "Ascendant", "초월자", "Immortal", "불멸", "Radiant", "레디언트");

    private WeeklyStatsCalculator() {
    }

    public static WeeklyStats calculate(List<ValorantMatchRecord> records, WeeklyReportPeriod period) {
        List<ValorantMatchRecord> current = between(records, period.startInstant(), period.endExclusive());
        List<ValorantMatchRecord> previous = between(records, period.previousStartInstant(), period.startInstant());
        return new WeeklyStats(week(current), week(previous), tierChange(lastRanked(current), lastRanked(previous)));
    }

    /** "Gold 1" → "골드 1". 배치 전·모르는 이름은 null이다. */
    public static String koreanTierLabel(String tierLabel) {
        if (tierLabel == null) {
            return null;
        }
        String[] parts = tierLabel.strip().split(" ", 2);
        String name = TIER_NAMES.get(parts[0]);
        if (name == null) {
            return null;
        }
        return parts.length == 2 ? name + " " + parts[1] : name;
    }

    private static List<ValorantMatchRecord> between(List<ValorantMatchRecord> records, OffsetDateTime from,
            OffsetDateTime to) {
        return records.stream()
                .filter(record -> !record.getStartedAt().isBefore(from) && record.getStartedAt().isBefore(to))
                .sorted(Comparator.comparing(ValorantMatchRecord::getStartedAt))
                .toList();
    }

    private static WeekStats week(List<ValorantMatchRecord> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        int kills = 0, deaths = 0, score = 0, rounds = 0, shots = 0, headshots = 0, wins = 0;
        for (ValorantMatchRecord match : matches) {
            kills += match.getKills();
            deaths += match.getDeaths();
            score += match.getScore();
            rounds += match.getRounds();
            headshots += match.getHeadshots();
            shots += match.getHeadshots() + match.getBodyshots() + match.getLegshots();
            wins += match.isWon() ? 1 : 0;
        }
        ValorantMatchRecord ranked = lastRanked(matches);
        return new WeekStats(
                matches.size(),
                wins,
                round(wins * 100.0 / matches.size(), 10),
                deaths == 0 ? null : round((double) kills / deaths, 100),
                shots == 0 ? null : round(headshots * 100.0 / shots, 10),
                rounds == 0 ? null : Math.round((float) score / rounds),
                ranked == null ? null : koreanTierLabel(ranked.getTierLabel()),
                breakdown(matches, ValorantMatchRecord::getMapName),
                breakdown(matches, ValorantMatchRecord::getAgentName)
        );
    }

    // 그 주에서 티어가 확인되는 마지막 경기.
    private static ValorantMatchRecord lastRanked(List<ValorantMatchRecord> matches) {
        for (int i = matches.size() - 1; i >= 0; i--) {
            ValorantMatchRecord match = matches.get(i);
            if (match.getTierId() != null && koreanTierLabel(match.getTierLabel()) != null) {
                return match;
            }
        }
        return null;
    }

    private static String tierChange(ValorantMatchRecord current, ValorantMatchRecord previous) {
        if (current == null || previous == null) {
            return null;
        }
        int diff = Integer.compare(current.getTierId(), previous.getTierId());
        return diff > 0 ? "UP" : diff < 0 ? "DOWN" : "SAME";
    }

    private static List<Breakdown> breakdown(List<ValorantMatchRecord> matches,
            Function<ValorantMatchRecord, String> key) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (ValorantMatchRecord match : matches) {
            String name = key.apply(match);
            if (name != null) {
                counts.computeIfAbsent(name, ignored -> new int[2])[match.isWon() ? 0 : 1]++;
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new Breakdown(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingInt((Breakdown item) -> item.wins() + item.losses()).reversed())
                .limit(MAX_BREAKDOWN)
                .toList();
    }

    private static double round(double value, int scale) {
        return (double) Math.round(value * scale) / scale;
    }
}
