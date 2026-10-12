package com.gamerin.backend.domain.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MembershipService.class)
class MembershipServiceStubTest {

    @Autowired
    private MembershipService membershipService;

    @Test
    void stubCanBeInjectedWithoutDatabaseAndReturnsInactiveForAllUsers() {
        UUID userId = UUID.randomUUID();

        assertThat(membershipService.isActive(userId)).isFalse();
        assertThat(membershipService.isActive(null)).isFalse();
        assertThat(membershipService.findActiveUserIds(List.of(userId))).isEmpty();
        assertThat(membershipService.findActiveUserIds(List.of())).isEmpty();
    }

    @Test
    void paymentHooksRemainInactiveUntilTheGrantImplementationIsAvailable() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        membershipService.grant(userId, paymentId);
        membershipService.revoke(paymentId);

        assertThat(membershipService.isActive(userId)).isFalse();
        assertThat(membershipService.findActiveUserIds(List.of(userId))).isEmpty();
    }
}
