package com.gamerin.backend.domain.user.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "mileage_wallets")
public class MileageWallet {
    
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private Long balance = 0L;

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

    public UUID getUserId() { return userId; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }
    
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    // 마일리지 차감 로직 (도메인 모델 내에 비즈니스 로직 포함)
    public void deduct(Long amount) {
        if (this.balance < amount) {
            throw new RuntimeException("마일리지가 부족합니다. (현재 잔액: " + this.balance + ")");
        }
        this.balance -= amount;
    }

    // 거절 시 마일리지 반환
    public void addBalance(Long amount) {
        if (amount < 0) {
            throw new RuntimeException("충전 금액은 0보다 커야 합니다.");
        }
        
        this.balance += amount;
    }
    




}
