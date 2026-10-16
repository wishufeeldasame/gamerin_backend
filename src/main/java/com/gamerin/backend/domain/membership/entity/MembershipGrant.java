package com.gamerin.backend.domain.membership.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.payment.entity.Payment;
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
import jakarta.persistence.Table;

@Entity
@Table(name = "membership_grants")
public class MembershipGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true)
    private Payment payment;

    @Column(name = "granted_from", nullable = false)
    private OffsetDateTime grantedFrom;

    @Column(name = "granted_until", nullable = false)
    private OffsetDateTime grantedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipGrantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MembershipGrant() {
    }

    public static MembershipGrant create(User user, Payment payment, OffsetDateTime grantedFrom,
            OffsetDateTime grantedUntil, OffsetDateTime createdAt) {
        MembershipGrant grant = new MembershipGrant();
        grant.user = user;
        grant.payment = payment;
        grant.grantedFrom = grantedFrom;
        grant.grantedUntil = grantedUntil;
        grant.status = MembershipGrantStatus.GRANTED;
        grant.createdAt = createdAt;
        return grant;
    }

    public void revoke() {
        status = MembershipGrantStatus.REVOKED;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return user.getId(); }
    public UUID getPaymentId() { return payment.getId(); }
    public OffsetDateTime getGrantedFrom() { return grantedFrom; }
    public OffsetDateTime getGrantedUntil() { return grantedUntil; }
    public MembershipGrantStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
