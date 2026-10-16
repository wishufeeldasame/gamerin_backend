package com.gamerin.backend.domain.report.entity;

import com.gamerin.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_penalties")
public class UserPenalty {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private Report report; // 원인이 된 신고 (선택)

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 30)
    private PenaltyType penaltyType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at")
    private OffsetDateTime endAt; // null이면 영구 정지

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "administered_by")
    private User administeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UserPenalty() {
    }

    public static UserPenalty create(User user, Report report,
            PenaltyType penaltyType, String reason, OffsetDateTime endAt,
            User administeredBy) {
        UserPenalty penalty = new UserPenalty();
        penalty.user = user;
        penalty.report = report;
        penalty.penaltyType = penaltyType;
        penalty.reason = reason;
        penalty.startAt = OffsetDateTime.now();
        penalty.endAt = endAt;
        penalty.isActive = true;
        penalty.administeredBy = administeredBy;
        return penalty;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public void deactivate() {
        this.isActive = false;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Report getReport() {
        return report;
    }

    public PenaltyType getPenaltyType() {
        return penaltyType;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getStartAt() {
        return startAt;
    }

    public OffsetDateTime getEndAt() {
        return endAt;
    }

    public boolean isActive() {
        return isActive;
    }

    public User getAdministeredBy() {
        return administeredBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}