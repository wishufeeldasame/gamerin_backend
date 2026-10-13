package com.gamerin.backend.domain.payment.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBuyerIdAndRequestId(UUID buyerId, UUID requestId);

    /**
     * 처리권 확보·결과 반영용 조건부 전이 (04 문서 6-6절).
     * 관찰한 status·status_changed_at이 그대로일 때만 갱신하며, 1이면 처리권을 얻은 것이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Payment p
               set p.status = :next, p.statusChangedAt = :now
             where p.id = :id
               and p.status = :observed
               and p.statusChangedAt = :observedAt
            """)
    int transition(@Param("id") UUID id,
                   @Param("observed") PaymentStatus observed,
                   @Param("observedAt") OffsetDateTime observedAt,
                   @Param("next") PaymentStatus next,
                   @Param("now") OffsetDateTime now);
}
