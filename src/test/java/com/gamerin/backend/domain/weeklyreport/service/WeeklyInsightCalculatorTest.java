package com.gamerin.backend.domain.weeklyreport.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.gamerin.backend.domain.valorant.model.MatchInsight;
import com.gamerin.backend.domain.valorant.model.MatchInsight.RoundGroup;
import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyInsight;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import org.junit.jupiter.api.Test;

class WeeklyInsightCalculatorTest {

    @Test
    void excludesMatchesWithoutInsightFromEvidenceButKeepsTotals() {
        RoundGroup g = new RoundGroup(10, 6, 8, 5);
        MatchInsight insight = new MatchInsight(null, 3, 2, 2, 1, g, g, g, g, 1, 2, g, g, g, null, false, true, 0);
        List<ValorantMatchRecord> records = List.of(
                record("2026-11-03T10:00:00Z", insight),
                record("2026-11-04T10:00:00Z", null),
                record("2026-10-27T10:00:00Z", null));

        WeeklyInsight result = WeeklyInsightCalculator.calculate(records, new WeeklyReportPeriod(LocalDate.of(2026, 11, 2)));

        WeeklyInsight.Stats current = result.current().overall();
        assertThat(current.matches()).isEqualTo(2);
        assertThat(current.insightMatches()).isEqualTo(1);
        assertThat(current.evidence().adr()).isNull();
        assertThat(current.evidence().firstKills()).isEqualTo(3);
        assertThat(current.evidence().attack()).isEqualTo(g);
        assertThat(result.current().afkMatches()).isEqualTo(1);
        assertThat(result.current().abilities()).isEmpty();
        assertThat(result.previous().overall().insightMatches()).isZero();
        assertThat(result.previous().overall().evidence()).isNull();
    }

    private static ValorantMatchRecord record(String startedAt, MatchInsight insight) {
        return ValorantMatchRecord.of("puuid", new ValorantMatchDetail("m" + startedAt, Instant.parse(startedAt),
                "Bind", "Jett", 12, "Gold 1", new ValorantMatchStats(20, 10, 5, 4800, 24, 8, 20, 2, true), insight));
    }
}
