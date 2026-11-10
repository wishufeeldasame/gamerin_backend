package com.gamerin.backend.domain.weeklyreport.model;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** 리포트 대상 주. 한 주는 Asia/Seoul 월요일 00:00부터 다음 월요일 00:00(미포함)까지다. */
public record WeeklyReportPeriod(LocalDate weekStart) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 직전에 끝난 주. */
    public static WeeklyReportPeriod lastCompleted(Clock clock) {
        LocalDate thisMonday = LocalDate.now(clock.withZone(ZONE))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new WeeklyReportPeriod(thisMonday.minusWeeks(1));
    }

    public LocalDate weekEnd() {
        return weekStart.plusDays(6);
    }

    public LocalDate previousWeekStart() {
        return weekStart.minusWeeks(1);
    }

    public LocalDate previousWeekEnd() {
        return weekStart.minusDays(1);
    }

    public OffsetDateTime startInstant() {
        return weekStart.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OffsetDateTime endExclusive() {
        return weekStart.plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OffsetDateTime previousStartInstant() {
        return previousWeekStart().atStartOfDay(ZONE).toOffsetDateTime();
    }
}
