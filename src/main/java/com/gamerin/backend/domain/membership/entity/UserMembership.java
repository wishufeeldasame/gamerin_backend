package com.gamerin.backend.domain.membership.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.payment.PaymentProduct;
import com.gamerin.backend.domain.user.entity.User;

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
@Table(name = "user_memberships")
public class UserMembership {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserMembership() {
    }

    public static UserMembership create(User user, OffsetDateTime expiresAt) {
        UserMembership membership = new UserMembership();
        membership.user = user;
        membership.expiresAt = expiresAt;
        return membership;
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    // The caller selects the start after checking the purchase and existing membership period (#89).
    public void applyMembershipPassFrom(OffsetDateTime grantedFrom) {
        this.expiresAt = grantedFrom.plus(PaymentProduct.MEMBERSHIP_30D.membershipDuration());
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
