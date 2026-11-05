package com.gamerin.backend.domain.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.entity.MembershipGrant;
import com.gamerin.backend.domain.membership.entity.MembershipGrantStatus;
import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.repository.MembershipGrantRepository;
import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class MembershipGrantServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T00:00:00Z");
    @Mock private UserMembershipRepository memberships;
    @Mock private MembershipGrantRepository grants;
    @Mock private PaymentRepository payments;
    @Mock private UserRepository users;
    private MembershipService service;
    private User user;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new MembershipService(memberships, grants, payments, users,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        user = User.createLocal("member@example.com", "member", "Member", "encoded");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        payment = Payment.create(user, UUID.randomUUID(), PaymentProduct.MEMBERSHIP_30D, "TC0ONETIME", NOW);
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.APPROVED);
    }

    @Test
    void firstGrantCreatesThirtyDaysAndHistoryFromTheSharedProduct() {
        prepareGrant();

        service.grant(user.getId(), payment.getId());

        ArgumentCaptor<UserMembership> membership = ArgumentCaptor.forClass(UserMembership.class);
        verify(memberships).saveAndFlush(membership.capture());
        assertThat(membership.getValue().getExpiresAt()).isEqualTo(NOW.plusDays(30));
        ArgumentCaptor<MembershipGrant> grant = ArgumentCaptor.forClass(MembershipGrant.class);
        verify(grants).save(grant.capture());
        assertThat(grant.getValue().getPaymentId()).isEqualTo(payment.getId());
        assertThat(grant.getValue().getGrantedFrom()).isEqualTo(NOW);
        assertThat(grant.getValue().getGrantedUntil()).isEqualTo(NOW.plusDays(30));
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 0, 5, 36500})
    void grantStartsAtTheLaterOfNowAndTheExistingExpiry(int remainingDays) {
        prepareGrant();
        UserMembership membership = UserMembership.create(user, NOW.plusDays(remainingDays));
        when(memberships.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(membership));

        service.grant(user.getId(), payment.getId());

        assertThat(membership.getExpiresAt()).isEqualTo(NOW.plusDays(Math.max(0, remainingDays) + 30));
        verify(memberships, never()).saveAndFlush(any());
    }

    @Test
    void duplicateApprovedGrantDoesNotExtendAgain() {
        prepareGrant();
        when(grants.findByPayment_Id(payment.getId())).thenReturn(Optional.of(grant(NOW, NOW.plusDays(30), NOW)));

        service.grant(user.getId(), payment.getId());

        verifyNoInteractions(memberships);
        verify(grants, never()).save(any());
    }

    @Test
    void replayOfARevokedPaymentCannotRestoreItAutomatically() {
        prepareGrant();
        MembershipGrant grant = grant(NOW, NOW.plusDays(30), NOW);
        grant.revoke();
        when(grants.findByPayment_Id(payment.getId())).thenReturn(Optional.of(grant));

        assertConflict(() -> service.grant(user.getId(), payment.getId()));

        assertThat(grant.getStatus()).isEqualTo(MembershipGrantStatus.REVOKED);
        verifyNoInteractions(memberships);
        verify(grants, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CREATED", "CANCELED", "NEEDS_REVIEW"})
    void nonApprovedPaymentCannotGrant(PaymentStatus status) {
        prepareGrant();
        ReflectionTestUtils.setField(payment, "status", status);
        assertConflict(() -> service.grant(user.getId(), payment.getId()));
        verifyNoInteractions(memberships, grants);
    }

    @Test
    void chargeOrAnotherBuyersPaymentCannotGrantMembership() {
        prepareGrant();
        ReflectionTestUtils.setField(payment, "purpose", PaymentProduct.CHARGE_5000.getPurpose());
        assertConflict(() -> service.grant(user.getId(), payment.getId()));
        ReflectionTestUtils.setField(payment, "purpose", PaymentProduct.MEMBERSHIP_30D.getPurpose());
        User another = User.createLocal("other@example.com", "other", "Other", "encoded");
        ReflectionTestUtils.setField(another, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(payment, "buyer", another);
        assertConflict(() -> service.grant(user.getId(), payment.getId()));
        verifyNoInteractions(memberships, grants);
    }

    @Test
    void equalClockPurchasesHaveStrictlyOrderedHistoryWithoutChangingThePeriodStart() {
        prepareGrant();
        MembershipGrant previous = grant(NOW, NOW.plusDays(30), NOW);
        when(grants.findFirstByUser_IdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(previous));
        UserMembership membership = UserMembership.create(user, NOW.plusDays(30));
        when(memberships.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(membership));

        service.grant(user.getId(), payment.getId());

        ArgumentCaptor<MembershipGrant> next = ArgumentCaptor.forClass(MembershipGrant.class);
        verify(grants).save(next.capture());
        assertThat(next.getValue().getCreatedAt()).isEqualTo(NOW.plusNanos(1000));
        assertThat(next.getValue().getGrantedFrom()).isEqualTo(NOW.plusDays(30));
        assertThat(next.getValue().getGrantedUntil()).isEqualTo(NOW.plusDays(60));
    }

    @Test
    void revokePreservesThePreviouslyPurchasedPeriodAndRetainsTheHistory() {
        MembershipGrant target = grant(NOW.plusDays(5), NOW.plusDays(35), NOW);
        UserMembership membership = prepareRevoke(target, target, target);

        service.revoke(payment.getId());

        assertThat(membership.getExpiresAt()).isEqualTo(NOW.plusDays(5));
        assertThat(target.getStatus()).isEqualTo(MembershipGrantStatus.REVOKED);
        assertThat(target.getGrantedFrom()).isEqualTo(NOW.plusDays(5));
        assertThat(target.getGrantedUntil()).isEqualTo(NOW.plusDays(35));
        verify(grants, never()).delete(any());
    }

    @Test
    void aLaterRevokedPurchaseStillRejectsRevokingTheEarlierGrantWith409() {
        MembershipGrant target = grant(NOW, NOW.plusDays(30), NOW);
        MembershipGrant later = grant(NOW.plusDays(30), NOW.plusDays(60), NOW.plusNanos(1000));
        later.revoke();
        UserMembership membership = prepareRevoke(target, later, target);

        assertConflict(() -> service.revoke(payment.getId()));

        assertThat(membership.getExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(target.getStatus()).isEqualTo(MembershipGrantStatus.GRANTED);
    }

    @Test
    void nonLatestGrantedHistoryAndInconsistentExpiryAreRejectedBeforeMutation() {
        MembershipGrant target = grant(NOW, NOW.plusDays(30), NOW);
        MembershipGrant later = grant(NOW.plusDays(30), NOW.plusDays(60), NOW.plusNanos(1000));
        UserMembership membership = prepareRevoke(target, target, later);
        assertConflict(() -> service.revoke(payment.getId()));
        when(grants.findFirstByUser_IdAndStatusOrderByCreatedAtDesc(user.getId(), MembershipGrantStatus.GRANTED))
                .thenReturn(Optional.of(target));
        membership.setExpiresAt(NOW.plusDays(45));
        assertConflict(() -> service.revoke(payment.getId()));
        assertThat(membership.getExpiresAt()).isEqualTo(NOW.plusDays(45));
        assertThat(target.getStatus()).isEqualTo(MembershipGrantStatus.GRANTED);
    }

    @Test
    void myMembershipReadsTheCurrentPeriodWithoutCreatingOrChangingRows() {
        when(users.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
        assertThat(service.getMyMembership(user.getId()).expiresAt()).isNull();
        UserMembership membership = UserMembership.create(user, NOW);
        when(memberships.findById(user.getId())).thenReturn(Optional.of(membership));
        assertThat(service.getMyMembership(user.getId()).active()).isFalse();
        membership.setExpiresAt(NOW.plusDays(1));
        assertThat(service.getMyMembership(user.getId()).active()).isTrue();
        verify(memberships, never()).save(any());
        verifyNoInteractions(grants, payments);
    }

    private void prepareGrant() {
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(payments.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    private UserMembership prepareRevoke(MembershipGrant target, MembershipGrant latest, MembershipGrant latestGranted) {
        UserMembership membership = UserMembership.create(user, target.getGrantedUntil());
        when(grants.findUserIdByPaymentId(payment.getId())).thenReturn(Optional.of(user.getId()));
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(memberships.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(membership));
        when(grants.findByPayment_Id(payment.getId())).thenReturn(Optional.of(target));
        when(grants.findFirstByUser_IdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(latest));
        when(grants.findFirstByUser_IdAndStatusOrderByCreatedAtDesc(user.getId(), MembershipGrantStatus.GRANTED))
                .thenReturn(Optional.of(latestGranted));
        return membership;
    }

    private MembershipGrant grant(OffsetDateTime from, OffsetDateTime until, OffsetDateTime createdAt) {
        MembershipGrant grant = MembershipGrant.create(user, payment, from, until, createdAt);
        ReflectionTestUtils.setField(grant, "id", UUID.randomUUID());
        return grant;
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
}
