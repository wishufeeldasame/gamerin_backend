package com.gamerin.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PasswordResetToken() {
    }

    public static PasswordResetToken issue(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        PasswordResetToken passwordResetToken = new PasswordResetToken();
        passwordResetToken.userId = userId;
        passwordResetToken.tokenHash = tokenHash;
        passwordResetToken.expiresAt = expiresAt;
        return passwordResetToken;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void use() {
        if (usedAt == null) {
            usedAt = OffsetDateTime.now();
        }
    }
}
