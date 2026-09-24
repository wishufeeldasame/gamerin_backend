package com.gamerin.backend.domain.report.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "report_counts")
public class ReportCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "report_count", nullable = false)
    private long reportCount;

    @Column(name = "is_hidden", nullable = false)
    private boolean isHidden;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ReportCount() {
    }

    public static ReportCount create(ReportTargetType targetType, UUID targetId) {
        ReportCount count = new ReportCount();
        count.targetType = targetType;
        count.targetId = targetId;
        count.reportCount = 0L;
        count.isHidden = false;
        return count;
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

    public void incrementCount() {
        this.reportCount++;
    }

    public void hide() {
        this.isHidden = true;
    }

    public void restore() {
        this.isHidden = false;
    }

    public UUID getId() {
        return id;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public long getReportCount() {
        return reportCount;
    }

    public boolean isHidden() {
        return isHidden;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}