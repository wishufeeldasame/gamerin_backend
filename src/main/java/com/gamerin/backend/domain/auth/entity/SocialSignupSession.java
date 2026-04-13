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
@Table(name = "social_signup_sessions")
public class SocialSignupSession {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "provider_display_name", length = 255)
    private String providerDisplayName;

    @Column(name = "signup_token_hash", nullable = false, unique = true, length = 255)
    private String signupTokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SocialSignupSession() {}

    public static SocialSignupSession create(String provider, String providerUserId, String providerEmail, String providerDisplayName, String signupTokenHash, long expirationMinutes) {
        SocialSignupSession session = new SocialSignupSession();
        session.provider = provider;
        session.providerUserId = providerUserId;
        session.providerEmail = providerEmail;
        session.providerDisplayName = providerDisplayName;
        session.signupTokenHash = signupTokenHash;
        session.expiresAt = OffsetDateTime.now().plusMinutes(expirationMinutes);
        session.createdAt = OffsetDateTime.now();
        return session;
    }

    public String getProvider() { 
        return provider; 
    }
    public String getProviderUserId() { 
        return providerUserId; 
    }
    public String getProviderEmail() { 
        return providerEmail; 
    }

    public String getProviderDisplayName() {
        return providerDisplayName; 
    }

    public boolean isExpired() { 
        return OffsetDateTime.now().isAfter(expiresAt); 
    }
}
