package com.gamerin.backend.domain.weeklyreport.service;

import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.repository.WeeklyGameReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 수집·분석이 끝난 후 DB 쓰기만 하나의 짧은 트랜잭션으로 처리한다. */
@Service
public class WeeklyReportPersistenceService {

    private final WeeklyGameReportRepository reportRepository;
    private final WeeklyReportNotificationService notificationService;

    public WeeklyReportPersistenceService(WeeklyGameReportRepository reportRepository,
            WeeklyReportNotificationService notificationService) {
        this.reportRepository = reportRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public WeeklyGameReport save(String puuid, String game, WeeklyReportPeriod period, WeeklyReportStatus status,
            WeeklyStats stats, WeeklyAnalysis analysis, String aiModel) {
        WeeklyGameReport report = reportRepository.findByPuuidAndGameAndWeekStart(puuid, game, period.weekStart())
                .orElseGet(() -> WeeklyGameReport.create(puuid, game, period.weekStart()));
        report.update(status, stats, analysis, aiModel);
        WeeklyGameReport saved = reportRepository.save(report);
        notificationService.notifyReady(saved);
        return saved;
    }
}
