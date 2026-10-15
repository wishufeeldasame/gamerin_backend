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
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    // 인증 전에는 코드 만료 시각, 인증 후에는 인증 토큰 만료 시각
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verification_token_hash", unique = true, length = 255)
    private String verificationTokenHash;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EmailVerification() {
    }

    public static EmailVerification issue(String email, String codeHash, OffsetDateTime expiresAt) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.codeHash = codeHash;
        verification.expiresAt = expiresAt;
        return verification;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void recordFailedAttempt() {
        attemptCount++;
    }

    public void markVerified(String verificationTokenHash, OffsetDateTime tokenExpiresAt) {
        this.verifiedAt = OffsetDateTime.now();
        this.verificationTokenHash = verificationTokenHash;
        this.expiresAt = tokenExpiresAt;
    }

    public void consume() {
        if (consumedAt == null) {
            consumedAt = OffsetDateTime.now();
        }
    }
}
