package com.gamerin.backend.domain.mentoring.entity;

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

@Entity
@Table(name = "mentoring_applications")
public class MentoringApplication {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id")
    private MentoringProgram program;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mentee_id")
    private User mentee;

    @Column(nullable = false)
    private Long appliedMileage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String message;

    /** 기본 수수료 요율 10% (1000bp). V31 마이그레이션의 컬럼 DEFAULT와 동일해야 한다. */
    public static final int DEFAULT_FEE_RATE_BP = 1000;

    @Column(name = "fee_rate_bp", nullable = false)
    private Integer feeRateBp = DEFAULT_FEE_RATE_BP;

    @Column(name = "fee_amount")
    private Long feeAmount;

    private OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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

    // Getters & Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public MentoringProgram getProgram() { return program; }
    public void setProgram(MentoringProgram program) { this.program = program; }

    public User getMentee() { return mentee; }
    public void setMentee(User mentee) { this.mentee = mentee; }


    public Long getAppliedMileage() { return appliedMileage; }
    public void setAppliedMileage(Long appliedMileage) { this.appliedMileage = appliedMileage; }

    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getFeeRateBp() { return feeRateBp; }
    public void setFeeRateBp(Integer feeRateBp) { this.feeRateBp = feeRateBp; }

    public Long getFeeAmount() { return feeAmount; }
    public void setFeeAmount(Long feeAmount) { this.feeAmount = feeAmount; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }




}
