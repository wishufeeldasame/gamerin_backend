package com.gamerin.backend.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PaymentProductTest {

    @Test
    void membershipPassDefinesTheAgreedPriceAndDuration() {
        assertThat(PaymentProduct.MEMBERSHIP_30D.amount()).isEqualTo(4900L);
        assertThat(PaymentProduct.MEMBERSHIP_30D.membershipDuration()).isEqualTo(Duration.ofDays(30));
    }
}
