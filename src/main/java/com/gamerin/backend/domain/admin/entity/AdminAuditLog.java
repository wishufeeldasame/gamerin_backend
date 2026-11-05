package com.gamerin.backend.domain.admin.entity;

import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.user.entity.User;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id")
    private User admin;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // CONTENT_HIDE, USER_BAN, REPORT_REJECT,CONTENT_RESTORE,FORCE_REFUND

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AdminAuditLog() {
    }

    public static AdminAuditLog create(User admin, String actionType, ReportTargetType targetType, UUID targetId,
            String requestId, String details) {
        AdminAuditLog log = new AdminAuditLog();
        log.admin = admin;
        log.actionType = actionType;
        log.targetType = targetType;
        log.targetId = targetId;
        log.requestId = requestId;
        log.details = details;
        return log;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public User getAdmin() {
        return admin;
    }

    public String getActionType() {
        return actionType;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDetails() {
        return details;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}