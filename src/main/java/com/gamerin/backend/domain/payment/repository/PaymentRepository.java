package com.gamerin.backend.domain.payment.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBuyerIdAndRequestId(UUID buyerId, UUID requestId);

    // 이용권 ready·취소 가드: User 행 잠금 아래에서 호출한다(04 문서 6-6절 결제 취소 2번).
    boolean existsByBuyerIdAndPurposeAndStatusIn(UUID buyerId, PaymentPurpose purpose,
                                                 Collection<PaymentStatus> statuses);

    /**
     * 처리권 확보·결과 반영용 조건부 전이 (04 문서 6-6절).
     * 관찰한 status·status_changed_at이 그대로일 때만 갱신하며, 1이면 처리권을 얻은 것이다.
     * next가 observed와 같으면 상태를 유지한 채 status_changed_at만 갱신한다(오래된 주문의 복구 착수).
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

    // 복구 스케줄러 대상: CREATED·APPROVING·CANCELING 1분 이상, READY는 ready 시각 + 15분 경과 (6-6절)
    @Query("""
            select p from Payment p
             where (p.status in :staleStatuses and p.statusChangedAt <= :staleBefore)
                or (p.status = com.gamerin.backend.domain.payment.entity.PaymentStatus.READY
                    and p.readyAt <= :readyBefore)
             order by p.statusChangedAt asc
            """)
    List<Payment> findRecoveryCandidates(@Param("staleStatuses") Collection<PaymentStatus> staleStatuses,
                                         @Param("staleBefore") OffsetDateTime staleBefore,
                                         @Param("readyBefore") OffsetDateTime readyBefore,
                                         Pageable pageable);

    // 결제 목록: (createdAt, id) 내림차순 커서
    @Query("""
            select p from Payment p
             where p.buyer.id = :buyerId
               and p.status in :statuses
             order by p.createdAt desc, p.id desc
            """)
    List<Payment> findFirstPage(@Param("buyerId") UUID buyerId,
                                @Param("statuses") Collection<PaymentStatus> statuses,
                                Pageable pageable);

    @Query("""
            select p from Payment p
             where p.buyer.id = :buyerId
               and p.status in :statuses
               and (p.createdAt < :cursorCreatedAt
                    or (p.createdAt = :cursorCreatedAt and p.id < :cursorId))
             order by p.createdAt desc, p.id desc
            """)
    List<Payment> findPageBefore(@Param("buyerId") UUID buyerId,
                                 @Param("statuses") Collection<PaymentStatus> statuses,
                                 @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
                                 @Param("cursorId") UUID cursorId,
                                 Pageable pageable);
}
