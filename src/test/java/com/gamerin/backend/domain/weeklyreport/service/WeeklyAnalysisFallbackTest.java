package com.gamerin.backend.domain.weeklyreport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Evidence;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Group;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Stats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight.Week;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import org.junit.jupiter.api.Test;

class WeeklyAnalysisFallbackTest {

    private static final WeeklyStats STATS = new WeeklyStats(
            new WeekStats(9, 3, 33.3, 1.0, 30.2, 238, "초월자 2", List.of(), List.of()), null, null);

    @Test
    void writesFirstDuelChangeAndTopComboWithoutScreenNumbers() {
        Stats current = stats(9, 237.6, evidence(154.2, 22, 18));
        Stats combo = stats(2, 250.0, evidence(160.0, 10, 8));
        Stats previous = stats(14, 260.4, evidence(173.5, 45, 47));
        WeeklyInsight insight = new WeeklyInsight(
                week(current, List.of(new Group("Haven × Jett", combo))), week(previous, List.of()));

        WeeklyAnalysis analysis = WeeklyAnalysisFallback.write(STATS, insight);

        assertThat(analysis.headline()).isEqualTo("첫 교전에서 먼저 처치한 비율이 오른 한 주");
        assertThat(analysis.summary()).contains("48.9%에서 이번 주 55%로", "Haven × Jett 조합(2경기)", "2경기뿐이라")
                .doesNotContain("승률", "K/D", "초월자");
        assertThat(analysis.focus()).contains("Haven × Jett");
    }

    @Test
    void defersWhenNoEvidence() {
        WeeklyInsight insight = new WeeklyInsight(week(stats(9, 237.6, null), List.of()), null);

        assertThat(WeeklyAnalysisFallback.write(STATS, insight).headline()).isEqualTo("상세 근거가 부족해 해석을 미룬 한 주");
        assertThat(WeeklyAnalysisFallback.write(STATS, null).summary()).contains("9경기");
    }

    @Test
    void skipsWeekOverWeekWhenPreviousEvidenceIsPartial() {
        Stats previous = new Stats(20, 0, 0, 0, 0, null, 260.4, null, 1, evidence(173.5, 1, 9));
        WeeklyInsight insight = new WeeklyInsight(
                week(stats(9, 237.6, evidence(154.2, 22, 18)), List.of()), week(previous, List.of()));

        WeeklyAnalysis analysis = WeeklyAnalysisFallback.write(STATS, insight);

        assertThat(analysis.headline()).isEqualTo("첫 교전과 주요 조합을 함께 돌아볼 한 주");
        assertThat(analysis.summary()).contains("첫 교전 40번 중").doesNotContain("전주");
    }

    @Test
    void doesNotAskForLossReviewAfterUnbeatenWeek() {
        WeeklyStats unbeaten = new WeeklyStats(
                new WeekStats(1, 1, 100.0, 1.0, 30.2, 238, "초월자 2", List.of(), List.of()), null, null);

        assertThat(WeeklyAnalysisFallback.write(unbeaten, null).focus()).doesNotContain("패배");
        assertThat(WeeklyAnalysisFallback.write(STATS, null).focus()).contains("패배한 경기");
    }

    private static Week week(Stats overall, List<Group> combos) {
        return new Week(overall, List.of(), List.of(), combos, null, null, List.of(), 0);
    }

    private static Stats stats(int matches, Double acs, Evidence evidence) {
        return new Stats(matches, 0, 0, 0, 0, null, acs, null, evidence == null ? 0 : matches, evidence);
    }

    private static Evidence evidence(double adr, int firstKills, int firstDeaths) {
        return new Evidence(adr, firstKills, firstDeaths, 0, 0, RoundGroup.EMPTY, RoundGroup.EMPTY, RoundGroup.EMPTY,
                RoundGroup.EMPTY, 0, 0, RoundGroup.EMPTY, RoundGroup.EMPTY, RoundGroup.EMPTY);
    }
}
