package com.gamerin.backend.domain.auth.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "social_accounts")
public class SocialAccount {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "provider_display_name", length = 255)
    private String providerDisplayName;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SocialAccount() {}

    public static SocialAccount create(UUID userId, String provider, String providerUserId, String providerEmail, String providerDisplayName) {
        SocialAccount account = new SocialAccount();
        account.userId = userId;
        account.provider = provider;
        account.providerUserId = providerUserId;
        account.providerEmail = providerEmail;
        account.providerDisplayName = providerDisplayName;
        account.linkedAt = OffsetDateTime.now();
        account.createdAt = OffsetDateTime.now();
        return account;
    }

    public void updateLastLoginAt() {
        this.lastLoginAt = OffsetDateTime.now();
    }

    public UUID getUserId() {
        return userId;
    }
}
