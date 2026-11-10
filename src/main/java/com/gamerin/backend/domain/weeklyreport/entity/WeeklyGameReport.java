package com.gamerin.backend.domain.weeklyreport.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 게임 계정(puuid)·게임·주(week_start, KST 월요일)마다 한 건인 주간 전적 리포트. */
@Entity
@Table(name = "weekly_game_reports", uniqueConstraints = @UniqueConstraint(
        name = "uk_weekly_game_reports_puuid_game_week", columnNames = {"puuid", "game", "week_start"}))
public class WeeklyGameReport {

    @Id private UUID id;
    @Column(nullable = false, length = 100) private String puuid;
    @Column(nullable = false, length = 20) private String game;
    @Column(name = "week_start", nullable = false) private LocalDate weekStart;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private WeeklyStats stats;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private WeeklyAnalysis analysis;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private WeeklyReportStatus status;
    @Column(name = "ai_model", length = 100) private String aiModel;
    @Column(name = "generated_at", nullable = false) private OffsetDateTime generatedAt;

    protected WeeklyGameReport() {}

    public static WeeklyGameReport create(String puuid, String game, LocalDate weekStart) {
        WeeklyGameReport report = new WeeklyGameReport();
        report.id = UUID.randomUUID();
        report.puuid = puuid;
        report.game = game;
        report.weekStart = weekStart;
        return report;
    }

    public void update(WeeklyReportStatus status, WeeklyStats stats, WeeklyAnalysis analysis, String aiModel) {
        this.status = status;
        this.stats = stats;
        this.analysis = analysis;
        this.aiModel = aiModel;
        this.generatedAt = OffsetDateTime.now();
    }

    public WeeklyStats getStats() { return stats; }
    public WeeklyAnalysis getAnalysis() { return analysis; }
    public WeeklyReportStatus getStatus() { return status; }
    public String getAiModel() { return aiModel; }
}
