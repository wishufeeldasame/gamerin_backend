package com.gamerin.backend.domain.report.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "reports")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // DB 시퀀스 자동 생성 (RPT-1001, RPT-1002...), 읽기 전용
    // 마이그레이션(V24)과 일치하도록 DB 시퀀스 기반으로 단일화하여 난수 충돌 가능성 원천 차단
    @Column(
            name = "report_code",
            nullable = false,
            insertable = false,
            updatable = false,
            length = 30,
            columnDefinition = "VARCHAR(30) DEFAULT ('RPT-' || lpad(cast(nextval('report_code_seq') as varchar), 4, '0'))"
    )
    @Generated(event = EventType.INSERT)
    private String reportCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id")
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    // 신고 접수 시점의 콘텐츠 스냅샷 (글/댓글 원본 보존용)
    @Column(name = "target_snippet", columnDefinition = "TEXT")
    private String targetSnippet;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReportReasonCode reasonCode;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_admin_id")
    private User assignedAdmin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Report() {
    }

    public static Report create(
            User reporter,
            ReportTargetType targetType,
            UUID targetId,
            String targetSnippet,
            ReportReasonCode reasonCode,
            String details
    ) {
        Report report = new Report();
        report.reporter = reporter;
        report.targetType = targetType;
        report.targetId = targetId;
        report.targetSnippet = targetSnippet;
        report.reasonCode = reasonCode;
        report.details = details;
        report.status = ReportStatus.RECEIVED;
        return report;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateStatus(ReportStatus status, User admin) {
        this.status = status;
        this.assignedAdmin = admin;
    }

    public UUID getId() {
        return id;
    }

    public String getReportCode() {
        return reportCode;
    }

    public User getReporter() {
        return reporter;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getTargetSnippet() {
        return targetSnippet;
    }

    public ReportReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getDetails() {
        return details;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public User getAssignedAdmin() {
        return assignedAdmin;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}