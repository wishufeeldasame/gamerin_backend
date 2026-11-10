package com.gamerin.backend.domain.weeklyreport.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import org.junit.jupiter.api.Test;

class WeeklyStatsCollectionTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WeeklyReportPeriod period = new WeeklyReportPeriod(LocalDate.parse("2026-11-02"));
    private final WeeklyReportCollectionIssue issue = new WeeklyReportCollectionIssue(
            "missing-match", Instant.parse("2026-11-02T15:27:02.659Z"), "Lotus", 404, "Match not found");

    @Test
    void readsExistingStatsWithoutDiagnosticsAndRoundTripsNewDiagnostics() throws Exception {
        WeeklyStats legacy = mapper.readValue("{\"current\":null,\"previous\":null,\"tierChange\":null}", WeeklyStats.class);
        assertThat(legacy.collectionIssues()).isEmpty();
        WeeklyStats stats = new WeeklyStats(null, null, null, List.of(issue));

        assertThat(mapper.readValue(mapper.writeValueAsString(stats), WeeklyStats.class)).isEqualTo(stats);
    }

    @Test
    void publicResponseOnlyShowsPlainNoticeWhileAdminGetsDiagnosticsEvenWithoutAvailableMatches() throws Exception {
        WeeklyStats stats = new WeeklyStats(null, null, null, List.of(issue));
        var member = WeeklyGameReportResponse.empty(period, stats, false);
        String memberJson = mapper.writeValueAsString(member);
        var admin = WeeklyGameReportResponse.empty(period, stats, true);

        assertThat(member.games()).isEmpty();
        assertThat(member.notice()).contains("일부 경기 정보를 불러오지 못했어요");
        assertThat(memberJson).doesNotContain("collectionIssues", "missing-match", "404", "Match not found");
        assertThat(admin.collectionIssues()).containsExactly(issue);
        assertThat(mapper.writeValueAsString(admin)).contains("collectionIssues", "missing-match", "404");
    }
}
