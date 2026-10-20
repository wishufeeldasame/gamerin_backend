package com.gamerin.backend.domain.membership.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.membership.entity.MembershipGrant;
import com.gamerin.backend.domain.membership.entity.MembershipGrantStatus;

public interface MembershipGrantRepository extends JpaRepository<MembershipGrant, UUID> {
    Optional<MembershipGrant> findByPayment_Id(UUID paymentId);

    @Query("select history.user.id from MembershipGrant history where history.payment.id = :paymentId")
    Optional<UUID> findUserIdByPaymentId(@Param("paymentId") UUID paymentId);

    Optional<MembershipGrant> findFirstByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<MembershipGrant> findFirstByUser_IdAndStatusOrderByCreatedAtDesc(UUID userId, MembershipGrantStatus status);
}
