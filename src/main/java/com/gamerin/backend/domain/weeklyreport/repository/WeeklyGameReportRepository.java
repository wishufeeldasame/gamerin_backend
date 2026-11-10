package com.gamerin.backend.domain.weeklyreport.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyGameReportRepository extends JpaRepository<WeeklyGameReport, UUID> {

    Optional<WeeklyGameReport> findByPuuidAndGameAndWeekStart(String puuid, String game, LocalDate weekStart);
}
