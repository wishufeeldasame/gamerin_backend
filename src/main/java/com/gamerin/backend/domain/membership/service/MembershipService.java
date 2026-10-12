package com.gamerin.backend.domain.membership.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class MembershipService {

    public boolean isActive(UUID userId) {
        return false;
    }

    public Set<UUID> findActiveUserIds(Collection<UUID> userIds) {
        return Set.of();
    }

    // #87 core replaces lookups; #89 implements payment hooks in the caller's transaction.
    public void grant(UUID userId, UUID paymentId) {
    }

    public void revoke(UUID paymentId) {
    }
}
