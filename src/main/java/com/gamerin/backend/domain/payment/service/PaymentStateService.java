package com.gamerin.backend.domain.payment.service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;

/**
 * 결제 상태 전이와 내부 반영(적립·회수)을 짧은 트랜잭션으로 수행한다. 외부 호출은 하지 않는다.
 * 모든 전이는 관찰한 status·status_changed_at 조건부 갱신으로 처리권을 확인한다(04 문서 6-6절).
 */
@Service
public class PaymentStateService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateService.class);

    private static final String CHARGE_DESCRIPTION = "카카오페이 충전";
    private static final String CHARGE_CANCEL_DESCRIPTION = "카카오페이 충전 취소";
    // 이용권 취소를 막는 미완료 이용권 주문 (6-6절 결제 취소 2번)
    private static final List<PaymentStatus> PENDING_STATUSES = List.of(
            PaymentStatus.CREATED, PaymentStatus.READY, PaymentStatus.APPROVING);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final MileageService mileageService;
    private final MembershipService membershipService;
    private final TransactionTemplate transactionTemplate;

    public PaymentStateService(PaymentRepository paymentRepository,
                               UserRepository userRepository,
                               MileageService mileageService,
                               MembershipService membershipService,
                               TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.mileageService = mileageService;
        this.membershipService = membershipService;
        this.transactionTemplate = transactionTemplate;
    }

    // DB(PostgreSQL)는 마이크로초까지 저장하므로 status_changed_at 비교가 어긋나지 않게 맞춘다.
    public static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Payment reload(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment disappeared: " + paymentId));
    }

    public boolean transition(UUID paymentId, PaymentStatus observed, OffsetDateTime observedAt,
                              PaymentStatus next, OffsetDateTime now) {
        Integer updated = transactionTemplate.execute(status ->
                paymentRepository.transition(paymentId, observed, observedAt, next, now));
        return updated != null && updated == 1;
    }

    public boolean transition(UUID paymentId, PaymentStatus observed, OffsetDateTime observedAt, PaymentStatus next) {
        return transition(paymentId, observed, observedAt, next, now());
    }

    /** 상태를 유지한 채 status_changed_at만 갱신해 복구 처리권을 잡는다. 실패하면 null. */
    public OffsetDateTime claim(Payment payment) {
        OffsetDateTime claimedAt = now();
        return transition(payment.getId(), payment.getStatus(), payment.getStatusChangedAt(),
                payment.getStatus(), claimedAt) ? claimedAt : null;
    }

    // 검증 후 한 트랜잭션에서 APPROVED 전환과 혜택 반영을 한다(6-2절 1·3·4번).
    public void completeApproval(Payment payment, OffsetDateTime claimedAt, ApprovalResult result) {
        if (!result.matches(payment)) {
            log.error("KakaoPay approval does not match stored order: orderId={}", payment.getId());
            transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.NEEDS_REVIEW);
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (paymentRepository.transition(payment.getId(), PaymentStatus.APPROVING, claimedAt,
                        PaymentStatus.APPROVED, now()) == 0) {
                    // 처리권을 잃은 늦은 응답은 새 상태를 덮어쓰지 않는다.
                    return;
                }
                Payment approved = reload(payment.getId());
                approved.recordApproval(result.aid(), result.approvedAt(), result.paymentMethodType());
                grantBenefit(approved);
            });
        } catch (TransientDataAccessException | RecoverableDataAccessException | DataAccessResourceFailureException
                 | TransactionException e) {
            // 일시적 DB·연결·트랜잭션 오류(락 타임아웃, 연결 끊김, 커밋 실패 등)는 APPROVING으로 남겨 복구에서 다시 시도한다(6-2절 5번).
            log.warn("Payment benefit reflection failed temporarily: orderId={}, error={}",
                    payment.getId(), e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            // 결제는 승인됐지만 혜택을 자동 반영할 수 없다(원장 UNIQUE 위반, 회수된 이용권 등).
            // 재시도해도 같은 실패가 반복되므로 롤백 후 별도 트랜잭션에서 수동 확인 대상으로 둔다(6-2절 6번).
            log.error("Payment benefit reflection failed, needs review: orderId={}, error={}",
                    payment.getId(), e.getClass().getSimpleName());
            transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.NEEDS_REVIEW);
        }
    }

    /**
     * 결제 취소 1단계: APPROVED → CANCELING 전환과 내부 회수를 한 트랜잭션에서 선반영한다(6-6절 결제 취소 2번).
     * 처리권을 잃으면 false. 잔액이 충전액보다 적으면 409로 거부하고 전환도 롤백한다.
     */
    public boolean beginCancel(Payment payment, OffsetDateTime claimedAt) {
        Boolean begun = transactionTemplate.execute(status -> {
            if (paymentRepository.transition(payment.getId(), PaymentStatus.APPROVED, payment.getStatusChangedAt(),
                    PaymentStatus.CANCELING, claimedAt) == 0) {
                return false;
            }
            Payment canceling = reload(payment.getId());
            revokeBenefit(canceling);
            return true;
        });
        return Boolean.TRUE.equals(begun);
    }

    // 외부 취소가 확정되면 CANCELING → CANCELED. 금액·식별자가 다르면 수동 확인 대상이다.
    public void completeCancel(Payment payment, OffsetDateTime claimedAt, CancelResult result) {
        if (!result.matches(payment)) {
            log.error("KakaoPay cancel does not match stored order: orderId={}", payment.getId());
            transition(payment.getId(), PaymentStatus.CANCELING, claimedAt, PaymentStatus.NEEDS_REVIEW);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (paymentRepository.transition(payment.getId(), PaymentStatus.CANCELING, claimedAt,
                    PaymentStatus.CANCELED, now()) == 1) {
                reload(payment.getId()).recordCancel(result.aid(), result.canceledAt());
            }
        });
    }

    private void grantBenefit(Payment payment) {
        if (payment.getPurpose() == PaymentPurpose.MILEAGE_CHARGE) {
            mileageService.addMileage(payment.getBuyer(), payment.getAmount(), TransactionType.CHARGE,
                    CHARGE_DESCRIPTION, payment.getId());
            return;
        }
        membershipService.grant(payment.getBuyer().getId(), payment.getId());
    }

    private void revokeBenefit(Payment payment) {
        if (payment.getPurpose() == PaymentPurpose.MILEAGE_CHARGE) {
            // 지갑을 잠그고 잔액이 충전액 이상일 때만 CHARGE_CANCEL로 회수한다.
            MileageWallet wallet = mileageService.getOrCreateWalletForUpdate(payment.getBuyer());
            if (wallet.getBalance() < payment.getAmount()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "충전한 마일리지를 이미 사용해 결제를 취소할 수 없습니다.");
            }
            mileageService.useMileage(payment.getBuyer(), payment.getAmount(), TransactionType.CHARGE_CANCEL,
                    CHARGE_CANCEL_DESCRIPTION, payment.getId());
            return;
        }
        // 이용권: 이용권 ready와 같은 User 행 잠금 아래에서 미완료 이용권 주문이 있으면 거부한다.
        // 마지막 GRANTED 확인·후속 구매 거부·기간 되돌리기는 revoke가 한다.
        UUID buyerId = payment.getBuyer().getId();
        userRepository.findByIdForUpdate(buyerId)
                .orElseThrow(() -> new IllegalStateException("Payment buyer disappeared: " + payment.getId()));
        if (paymentRepository.existsByBuyerIdAndPurposeAndStatusIn(buyerId, PaymentPurpose.MEMBERSHIP_PASS,
                PENDING_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "진행 중인 이용권 결제가 있어 결제를 취소할 수 없습니다.");
        }
        membershipService.revoke(payment.getId());
    }

    public record ApprovalResult(
            String aid,
            String tid,
            String cid,
            String partnerOrderId,
            String partnerUserId,
            Long amountTotal,
            String paymentMethodType,
            OffsetDateTime approvedAt
    ) {
        // 금액·CID·TID·주문번호·사용자 식별자가 저장한 주문과 같아야 한다(6-2절 1번, 6-5절).
        private boolean matches(Payment payment) {
            return aid != null && !aid.isBlank()
                    && payment.getAmount().equals(amountTotal)
                    && payment.getCid().equals(cid)
                    && payment.getTid().equals(tid)
                    && payment.getId().toString().equals(partnerOrderId)
                    && payment.getBuyer().getId().toString().equals(partnerUserId);
        }
    }

    public record CancelResult(
            String aid,
            String tid,
            String cid,
            Long canceledAmount,
            OffsetDateTime canceledAt
    ) {
        // 전액 취소만 하므로 취소 금액이 결제 금액과 같아야 한다(6-5절).
        private boolean matches(Payment payment) {
            return payment.getAmount().equals(canceledAmount)
                    && payment.getCid().equals(cid)
                    && payment.getTid().equals(tid);
        }
    }
}
