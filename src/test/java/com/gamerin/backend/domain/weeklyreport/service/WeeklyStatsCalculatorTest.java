package com.gamerin.backend.domain.weeklyreport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats.Breakdown;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import org.junit.jupiter.api.Test;

class WeeklyStatsCalculatorTest {

    // 대상 주: KST 2026-11-02(월) 00:00 ~ 11-09(월) 00:00, 전주: 10-26 ~ 11-02
    private static final WeeklyReportPeriod PERIOD = new WeeklyReportPeriod(LocalDate.parse("2026-11-02"));

    @Test
    void splitsWeeksAtSeoulMondayMidnight() {
        WeeklyStats stats = WeeklyStatsCalculator.calculate(List.of(
                match("too-old", "2026-10-25T14:59:59Z", "Bind", "Jett", 11, "Silver 3", 10, 10, true),
                match("prev-first", "2026-10-25T15:00:00Z", "Bind", "Jett", 11, "Silver 3", 10, 10, true),
                match("prev-last", "2026-11-01T14:59:59Z", "Bind", "Jett", 12, "Gold 1", 10, 10, false),
                match("cur-first", "2026-11-01T15:00:00Z", "Haven", "Sova", 12, "Gold 1", 30, 10, true),
                match("cur-last", "2026-11-08T14:59:59Z", "Bind", "Sova", 13, "Gold 2", 10, 10, false),
                match("too-new", "2026-11-08T15:00:00Z", "Bind", "Sova", 14, "Gold 3", 10, 10, true)
        ), PERIOD);

        WeekStats current = stats.current();
        assertThat(current.matches()).isEqualTo(2);
        assertThat(current.wins()).isEqualTo(1);
        assertThat(current.winRate()).isEqualTo(50.0);
        assertThat(current.kd()).isEqualTo(2.0);
        // 경기마다 헤드 8, 바디 20, 레그 2 → 8/30
        assertThat(current.headshotRate()).isEqualTo(26.7);
        // 경기마다 4800점 / 24라운드
        assertThat(current.acs()).isEqualTo(200);
        assertThat(current.tierLabel()).isEqualTo("골드 2");
        assertThat(current.maps()).containsExactlyInAnyOrder(new Breakdown("Haven", 1, 0), new Breakdown("Bind", 0, 1));
        assertThat(current.agents()).containsExactly(new Breakdown("Sova", 1, 1));
        assertThat(stats.previous().matches()).isEqualTo(2);
        assertThat(stats.previous().tierLabel()).isEqualTo("골드 1");
        assertThat(stats.tierChange()).isEqualTo("UP");
    }

    @Test
    void emptyWeeksAreNull() {
        WeeklyStats none = WeeklyStatsCalculator.calculate(List.of(), PERIOD);
        assertThat(none.current()).isNull();
        assertThat(none.previous()).isNull();
        assertThat(none.tierChange()).isNull();

        WeeklyStats noPrevious = WeeklyStatsCalculator.calculate(List.of(
                match("cur", "2026-11-03T00:00:00Z", "Bind", "Jett", 12, "Gold 1", 10, 10, true)), PERIOD);
        assertThat(noPrevious.current().matches()).isEqualTo(1);
        assertThat(noPrevious.previous()).isNull();
        assertThat(noPrevious.tierChange()).isNull();
    }

    @Test
    void zeroDeathsAndUnrankedGiveNulls() {
        WeekStats current = WeeklyStatsCalculator.calculate(List.of(
                match("cur", "2026-11-03T00:00:00Z", "Bind", "Jett", 0, "Unrated", 10, 0, true)), PERIOD).current();

        assertThat(current.kd()).isNull();
        assertThat(current.tierLabel()).isNull();
        assertThat(current.winRate()).isEqualTo(100.0);
    }

    @Test
    void translatesTierLabels() {
        assertThat(WeeklyStatsCalculator.koreanTierLabel("Gold 1")).isEqualTo("골드 1");
        assertThat(WeeklyStatsCalculator.koreanTierLabel("Radiant")).isEqualTo("레디언트");
        assertThat(WeeklyStatsCalculator.koreanTierLabel("Unrated")).isNull();
        assertThat(WeeklyStatsCalculator.koreanTierLabel(null)).isNull();
    }

    static ValorantMatchRecord match(String id, String startedAt, String map, String agent, Integer tierId,
            String tierLabel, int kills, int deaths, boolean won) {
        return ValorantMatchRecord.of("puuid", new ValorantMatchDetail(id, Instant.parse(startedAt), map, agent,
                tierId, tierLabel, new ValorantMatchStats(kills, deaths, 5, 4800, 24, 8, 20, 2, won), null));
    }
}
