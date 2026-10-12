package com.gamerin.backend.domain.membership.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;

@Service
public class MembershipService {

    private final UserMembershipRepository membershipRepository;
    private final Clock clock;

    @Autowired
    public MembershipService(UserMembershipRepository membershipRepository) {
        this(membershipRepository, Clock.systemUTC());
    }

    MembershipService(UserMembershipRepository membershipRepository, Clock clock) {
        this.membershipRepository = membershipRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return userId != null && !membershipRepository.findActiveUserIds(List.of(userId), now()).isEmpty();
    }

    @Transactional(readOnly = true)
    public Set<UUID> findActiveUserIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(membershipRepository.findActiveUserIds(userIds, now()));
    }

    private OffsetDateTime now() {
        // Match PostgreSQL's timestamp precision for both lookup paths.
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    // Payment grant/revoke are implemented in #89, within the caller's transaction.
    public void grant(UUID userId, UUID paymentId) {
    }

    public void revoke(UUID paymentId) {
    }
}
