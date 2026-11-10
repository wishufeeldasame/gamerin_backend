package com.gamerin.backend.domain.weeklyreport.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeeklyReportPeriodTest {

    @ParameterizedTest
    @CsvSource({
            // KST 월요일 00:00은 UTC 일요일 15:00이다.
            "2026-11-08T14:59:59Z, 2026-10-26", // KST 일요일 23:59 → 아직 11/2 주가 끝나지 않았다
            "2026-11-08T15:00:00Z, 2026-11-02", // KST 월요일 00:00
            "2026-11-09T19:00:00Z, 2026-11-02", // KST 화요일 04:00
            "2026-11-15T14:59:59Z, 2026-11-02"
    })
    void lastCompletedWeekFollowsSeoulMonday(String now, LocalDate expectedWeekStart) {
        WeeklyReportPeriod period = WeeklyReportPeriod.lastCompleted(Clock.fixed(Instant.parse(now), ZoneOffset.UTC));

        assertThat(period.weekStart()).isEqualTo(expectedWeekStart);
        assertThat(period.weekEnd()).isEqualTo(expectedWeekStart.plusDays(6));
        assertThat(period.previousWeekStart()).isEqualTo(expectedWeekStart.minusDays(7));
        assertThat(period.previousWeekEnd()).isEqualTo(expectedWeekStart.minusDays(1));
        assertThat(period.startInstant().toInstant())
                .isEqualTo(expectedWeekStart.atStartOfDay(ZoneOffset.ofHours(9)).toInstant());
        assertThat(period.endExclusive().toInstant())
                .isEqualTo(expectedWeekStart.plusDays(7).atStartOfDay(ZoneOffset.ofHours(9)).toInstant());
    }
}
