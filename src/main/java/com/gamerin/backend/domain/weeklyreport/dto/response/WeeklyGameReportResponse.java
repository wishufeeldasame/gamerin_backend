package com.gamerin.backend.domain.weeklyreport.dto.response;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportCollectionIssue;

/** 프론트 WeeklyGameReport와 같은 형태. 리포트가 없으면 games가 비어 있다. */
public record WeeklyGameReportResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        LocalDate previousWeekStart,
        LocalDate previousWeekEnd,
        int totalMatches,
        List<GameStat> games,
        @JsonInclude(JsonInclude.Include.NON_NULL) String notice,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<WeeklyReportCollectionIssue> collectionIssues
) {

    public record GameStat(
            String game,
            String gameLabel,
            int matches,
            double winRate,
            Double kd,
            Double headshotRate,
            String tierLabel,
            PreviousWeek previousWeek,
            WeeklyAnalysis analysis
    ) {
    }

    public record PreviousWeek(int matches, double winRate, Double kd, Double headshotRate, String tierLabel) {
    }

    public static WeeklyGameReportResponse empty(WeeklyReportPeriod period) {
        return empty(period, null, false);
    }

    public static WeeklyGameReportResponse empty(WeeklyReportPeriod period, WeeklyStats stats, boolean admin) {
        return of(period, List.of(), stats, admin);
    }

    public static WeeklyGameReportResponse valorant(WeeklyReportPeriod period, WeeklyStats stats,
            WeeklyAnalysis analysis) {
        return valorant(period, stats, analysis, false);
    }

    public static WeeklyGameReportResponse valorant(WeeklyReportPeriod period, WeeklyStats stats,
            WeeklyAnalysis analysis, boolean admin) {
        WeekStats current = stats.current();
        WeekStats previous = stats.previous();
        return of(period, List.of(new GameStat(
                "VALORANT",
                "발로란트",
                current.matches(),
                current.winRate(),
                current.kd(),
                current.headshotRate(),
                current.tierLabel(),
                previous == null ? null : new PreviousWeek(previous.matches(), previous.winRate(), previous.kd(),
                        previous.headshotRate(), previous.tierLabel()),
                analysis
        )), stats, admin);
    }

    private static WeeklyGameReportResponse of(WeeklyReportPeriod period, List<GameStat> games,
            WeeklyStats stats, boolean admin) {
        List<WeeklyReportCollectionIssue> issues = stats == null ? List.of() : stats.collectionIssues();
        String notice = issues.isEmpty() ? null
                : "일부 경기 정보를 불러오지 못했어요. 확인된 경기만으로 작성해 실제 기록과 차이가 있을 수 있어요.";
        return new WeeklyGameReportResponse(period.weekStart(), period.weekEnd(), period.previousWeekStart(),
                period.previousWeekEnd(), games.stream().mapToInt(GameStat::matches).sum(), games, notice,
                admin ? issues : null);
    }
}
