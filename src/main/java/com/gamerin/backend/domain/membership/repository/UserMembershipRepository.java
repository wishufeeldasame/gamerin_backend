package com.gamerin.backend.domain.membership.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.membership.entity.UserMembership;
import jakarta.persistence.LockModeType;

public interface UserMembershipRepository extends JpaRepository<UserMembership, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select membership from UserMembership membership where membership.userId = :userId")
    Optional<UserMembership> findByUserIdForUpdate(@Param("userId") UUID userId);

    @Query("""
        select membership.userId
        from UserMembership membership
        where membership.userId in :userIds
          and membership.expiresAt > :now
        """)
    List<UUID> findActiveUserIds(@Param("userIds") Collection<UUID> userIds,
                               @Param("now") OffsetDateTime now);
}
