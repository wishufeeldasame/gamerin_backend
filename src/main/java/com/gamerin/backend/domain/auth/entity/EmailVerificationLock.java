package com.gamerin.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_verification_locks")
public class EmailVerificationLock {

    @Id
    @Column(length = 255)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EmailVerificationLock() {
    }

    public static EmailVerificationLock of(String email) {
        EmailVerificationLock lock = new EmailVerificationLock();
        lock.email = email;
        return lock;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public String getEmail() {
        return email;
    }
}
