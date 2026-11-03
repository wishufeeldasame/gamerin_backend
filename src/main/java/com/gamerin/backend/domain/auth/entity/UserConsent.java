package com.gamerin.backend.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_consents")
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30)
    private ConsentType consentType;

    @Column(nullable = false, length = 30)
    private String version;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private OffsetDateTime agreedAt;

    protected UserConsent() {
    }

    public static UserConsent create(UUID userId, ConsentType consentType, String version, OffsetDateTime agreedAt) {
        UserConsent consent = new UserConsent();
        consent.userId = userId;
        consent.consentType = consentType;
        consent.version = version;
        consent.agreedAt = agreedAt;
        return consent;
    }

    public UUID getUserId() {
        return userId;
    }

    public ConsentType getConsentType() {
        return consentType;
    }

    public String getVersion() {
        return version;
    }

    public OffsetDateTime getAgreedAt() {
        return agreedAt;
    }
}
