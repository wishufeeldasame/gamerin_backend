package com.gamerin.backend.domain.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.membership.repository.MembershipGrantRepository;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MembershipServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-12T00:00:00.123456789Z");
    private static final OffsetDateTime QUERY_TIME = OffsetDateTime.parse("2026-10-12T00:00:00.123456Z");

    @Mock
    private UserMembershipRepository membershipRepository;
    @Mock private MembershipGrantRepository grantRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;

    private MembershipService membershipService;

    @BeforeEach
    void setUp() {
        membershipService = new MembershipService(membershipRepository, grantRepository, paymentRepository, userRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void isActiveReturnsTrueForAnActiveMembership() {
        UUID userId = UUID.randomUUID();
        when(membershipRepository.findActiveUserIds(anyCollection(), eq(QUERY_TIME))).thenReturn(List.of(userId));

        assertThat(membershipService.isActive(userId)).isTrue();
        verify(membershipRepository).findActiveUserIds(List.of(userId), QUERY_TIME);
        verifyNoMoreInteractions(membershipRepository);
    }

    @Test
    void isActiveReturnsFalseWhenNoActiveMembershipExists() {
        UUID userId = UUID.randomUUID();
        when(membershipRepository.findActiveUserIds(anyCollection(), eq(QUERY_TIME))).thenReturn(List.of());

        assertThat(membershipService.isActive(userId)).isFalse();
        verify(membershipRepository).findActiveUserIds(List.of(userId), QUERY_TIME);
    }

    @Test
    void isActiveWithNullUserIdDoesNotQuery() {
        assertThat(membershipService.isActive(null)).isFalse();
        verifyNoInteractions(membershipRepository);
    }

    @Test
    void emptyBulkLookupDoesNotQuery() {
        assertThat(membershipService.findActiveUserIds(List.of())).isEmpty();
        verifyNoInteractions(membershipRepository);
    }

    @Test
    void nullBulkLookupDoesNotQuery() {
        assertThat(membershipService.findActiveUserIds(null)).isEmpty();
        verifyNoInteractions(membershipRepository);
    }

    @Test
    void bulkLookupUsesOneQueryAndReturnsUniqueActiveIds() {
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        List<UUID> requestedIds = List.of(activeId, inactiveId, activeId);
        when(membershipRepository.findActiveUserIds(anyCollection(), eq(QUERY_TIME)))
                .thenReturn(List.of(activeId, activeId));

        Set<UUID> result = membershipService.findActiveUserIds(requestedIds);

        assertThat(result).containsExactly(activeId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(membershipRepository).findActiveUserIds(ids.capture(), eq(QUERY_TIME));
        assertThat(ids.getValue()).containsOnly(activeId, inactiveId);
        verifyNoMoreInteractions(membershipRepository);
    }

    @Test
    void timeZoneOfTheClockDoesNotChangeTheQueryInstant() {
        MembershipService localClockService = new MembershipService(membershipRepository, grantRepository, paymentRepository, userRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.ofHours(9)));
        UUID userId = UUID.randomUUID();
        when(membershipRepository.findActiveUserIds(anyCollection(), eq(QUERY_TIME))).thenReturn(List.of(userId));

        assertThat(localClockService.isActive(userId)).isTrue();
        verify(membershipRepository).findActiveUserIds(List.of(userId), QUERY_TIME);
    }

    @Test
    void missingUserAndGrantAreRejectedWithoutWritingMembershipState() {
        UUID userId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> membershipService.grant(userId, paymentId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> membershipService.revoke(paymentId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));

        verifyNoInteractions(membershipRepository);
    }
}
