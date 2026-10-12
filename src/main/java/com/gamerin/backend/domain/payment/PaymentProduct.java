package com.gamerin.backend.domain.payment;

import java.time.Duration;

public enum PaymentProduct {
    MEMBERSHIP_30D(4900L, Duration.ofDays(30));

    private final long amount;
    private final Duration membershipDuration;

    PaymentProduct(long amount, Duration membershipDuration) {
        this.amount = amount;
        this.membershipDuration = membershipDuration;
    }

    public long amount() {
        return amount;
    }

    public Duration membershipDuration() {
        return membershipDuration;
    }
}
