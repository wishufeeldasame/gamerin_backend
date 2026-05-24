package com.gamerin.backend.domain.user.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "mileage_transactions")
@EntityListeners(AuditingEntityListener.class)
public class MileageTransaction {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Long amount; // 변동 금액 (음수 가능)

    @Column(nullable = false)
    private Long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    private String description;

    private UUID referenceId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
    }


    protected MileageTransaction() {
    }

    public MileageTransaction(User user, Long amount, Long balanceAfter, TransactionType type, String description, UUID referenceId) {
        this.user = user;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.type = type;
        this.description = description;
        this.referenceId = referenceId;
    }

    public static MileageTransactionBuilder builder() {
        return new MileageTransactionBuilder();
    }

    public static class MileageTransactionBuilder {
        private User user;
        private Long amount;
        private Long balanceAfter;
        private TransactionType type;
        private String description;
        private UUID referenceId;
    

        MileageTransactionBuilder() {

        }

        public MileageTransactionBuilder user(User user) {
            this.user = user;
            return this;
        }

        public MileageTransactionBuilder amount(Long amount) {
            this.amount = amount;
            return this;
        }

        public MileageTransactionBuilder balanceAfter(Long balanceAfter) {
            this.balanceAfter = balanceAfter;
            return this;
        }

        public MileageTransactionBuilder type(TransactionType type) {
            this.type = type;
            return this;
        }

        public MileageTransactionBuilder description(String description) {
            this.description = description;
            return this;
        }

        public MileageTransactionBuilder referenceId(UUID referenceId) {
            this.referenceId = referenceId;
            return this;
        }

        public MileageTransaction build() {
            return new MileageTransaction(user, amount, balanceAfter, type, description, referenceId);
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Long getAmount() {
        return amount;
    }

    public Long getBalanceAfter() {
        return balanceAfter;
    }

    public TransactionType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }


}
