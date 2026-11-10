package com.gamerin.backend.domain.weeklyreport.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportCollectionIssue;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:weekly_report_collection;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;INIT=CREATE SEQUENCE IF NOT EXISTS report_code_seq START WITH 1001")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WeeklyGameReportCollectionPersistenceTest {

    @Autowired private WeeklyGameReportRepository repository;
    @Autowired private EntityManager entityManager;

    @Test
    void persistsCollectionIssuesInsideTheExistingStatsJson() {
        LocalDate week = LocalDate.parse("2026-11-02");
        WeeklyReportCollectionIssue issue = new WeeklyReportCollectionIssue("missing-match",
                Instant.parse("2026-11-02T15:27:02.659Z"), "Lotus", 404, "Match not found");
        WeeklyGameReport report = WeeklyGameReport.create("collection-test", "VALORANT", week);
        report.update(WeeklyReportStatus.NO_DATA, new WeeklyStats(null, null, null, List.of(issue)), null, null);
        repository.saveAndFlush(report);
        entityManager.clear();

        WeeklyGameReport restored = repository.findByPuuidAndGameAndWeekStart("collection-test", "VALORANT", week).orElseThrow();

        assertThat(restored.getStats().collectionIssues()).containsExactly(issue);
    }
}
