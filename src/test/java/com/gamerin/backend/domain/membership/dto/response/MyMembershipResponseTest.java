package com.gamerin.backend.domain.membership.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.gamerin.backend.domain.payment.model.PaymentProduct;

class MyMembershipResponseTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T12:00:00Z");

    @Test
    void returnsSharedProductAndThreeBenefitsWithoutMembership() {
        MyMembershipResponse response = MyMembershipResponse.from(null, NOW);

        assertThat(response.active()).isFalse();
        assertThat(response.expiresAt()).isNull();
        assertThat(response.price()).isEqualTo(PaymentProduct.MEMBERSHIP_30D.getPrice());
        assertThat(response.durationDays()).isEqualTo(PaymentProduct.MEMBERSHIP_30D.getDurationDays());
        assertThat(response.benefits()).containsExactly(
                "멤버십 배지",
                "최초 게시 후 1시간 이내 게시물 최대 1회 수정",
                "멘토 정산 수수료 5%"
        );
    }

    @Test
    void activeRequiresExpirationStrictlyAfterCurrentInstant() {
        assertThat(MyMembershipResponse.from(NOW.plusNanos(1_000), NOW).active()).isTrue();
        assertThat(MyMembershipResponse.from(NOW, NOW).active()).isFalse();
        assertThat(MyMembershipResponse.from(NOW.minusNanos(1_000), NOW).active()).isFalse();
    }

    @Test
    void comparesInstantsRegardlessOfOffsetAndRetainsStoredExpiration() {
        OffsetDateTime sameInstant = OffsetDateTime.parse("2026-10-15T21:00:00+09:00");
        MyMembershipResponse expired = MyMembershipResponse.from(sameInstant, NOW);

        assertThat(expired.active()).isFalse();
        assertThat(expired.expiresAt()).isEqualTo(sameInstant);
        assertThat(MyMembershipResponse.from(sameInstant.plusSeconds(1), NOW).active()).isTrue();
    }
}
