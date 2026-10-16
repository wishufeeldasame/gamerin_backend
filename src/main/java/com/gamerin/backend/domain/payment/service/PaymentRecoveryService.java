package com.gamerin.backend.domain.payment.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gamerin.backend.domain.payment.client.KakaoPayClient;
import com.gamerin.backend.domain.payment.client.KakaoPayException;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;

/**
 * 카카오페이 주문 조회로 미확정 주문을 정리하고, 외부 결제 취소를 호출한다 (04 문서 6-2·6-6절).
 * 조회 시점(GET·approve·cancel)과 복구 스케줄러가 같은 메서드를 쓴다.
 * 설정 오류(KakaoPayException CONFIGURATION)는 상태를 유지한 채 호출한 쪽으로 다시 던진다.
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    // read timeout(15초)보다 충분히 긴 결과 불명 판정 기준 (6-6절)
    static final Duration RESULT_UNKNOWN_AFTER = Duration.ofMinutes(1);
    static final int RECOVERY_BATCH_SIZE = 100;

    private static final String SUCCESS = "SUCCESS_PAYMENT";
    private static final String CANCELED = "CANCEL_PAYMENT";
    private static final String PART_CANCELED = "PART_CANCEL_PAYMENT";
    private static final List<String> FAILED = List.of("FAIL_PAYMENT", "QUIT_PAYMENT", "FAIL_AUTH_PASSWORD");

    private final PaymentRepository paymentRepository;
    private final PaymentStateService stateService;
    private final KakaoPayClient kakaoPayClient;

    public PaymentRecoveryService(PaymentRepository paymentRepository,
                                  PaymentStateService stateService,
                                  KakaoPayClient kakaoPayClient) {
        this.paymentRepository = paymentRepository;
        this.stateService = stateService;
        this.kakaoPayClient = kakaoPayClient;
    }

    /** CREATED·APPROVING·CANCELING 1분 이상, READY 15분 경과면 복구 대상이다. */
    public boolean needsRecovery(Payment payment, OffsetDateTime now) {
        return switch (payment.getStatus()) {
            case CREATED, APPROVING, CANCELING ->
                    !payment.getStatusChangedAt().plus(RESULT_UNKNOWN_AFTER).isAfter(now);
            case READY -> payment.isReadyExpired(now);
            default -> false;
        };
    }

    /** 조회 시점 복구: 복구 대상이면 복구를 시도하고 최신 주문을 돌려준다. */
    public Payment recoverIfNeeded(Payment payment) {
        if (!needsRecovery(payment, PaymentStateService.now())) {
            return payment;
        }
        recover(payment);
        return stateService.reload(payment.getId());
    }

    /** 스케줄러: 오래된 미확정 주문을 한 번에 RECOVERY_BATCH_SIZE건까지 정리한다. */
    public int recoverStalePayments() {
        OffsetDateTime now = PaymentStateService.now();
        List<Payment> candidates = paymentRepository.findRecoveryCandidates(
                List.of(PaymentStatus.CREATED, PaymentStatus.APPROVING, PaymentStatus.CANCELING),
                now.minus(RESULT_UNKNOWN_AFTER),
                now.minus(Payment.READY_TTL),
                PageRequest.of(0, RECOVERY_BATCH_SIZE)
        );
        for (Payment payment : candidates) {
            try {
                recover(payment);
            } catch (RuntimeException e) {
                // 조회 실패·설정 오류는 상태를 유지하고 다음 주기에 다시 시도한다.
                log.error("Payment recovery failed: orderId={}, status={}, error={}",
                        payment.getId(), payment.getStatus(), e.getClass().getSimpleName());
            }
        }
        return candidates.size();
    }

    private void recover(Payment payment) {
        switch (payment.getStatus()) {
            // TID가 없어 조회할 수 없으므로 FAILED로 닫는다.
            case CREATED -> stateService.transition(payment.getId(), PaymentStatus.CREATED,
                    payment.getStatusChangedAt(), PaymentStatus.FAILED);
            case READY -> withClaim(payment, claimedAt -> recoverReady(payment, claimedAt));
            case APPROVING -> withClaim(payment, claimedAt -> lookup(payment)
                    .ifPresent(order -> resolveApproving(payment, claimedAt, order)));
            case CANCELING -> withClaim(payment, claimedAt -> lookup(payment)
                    .ifPresent(order -> resolveCanceling(payment, claimedAt, order)));
            default -> {
            }
        }
    }

    // 처리권을 얻은 작업자만 외부 조회를 한다. 처리권을 잃으면 다른 작업자가 처리 중이다.
    private void withClaim(Payment payment, Consumer<OffsetDateTime> action) {
        OffsetDateTime claimedAt = stateService.claim(payment);
        if (claimedAt != null) {
            action.accept(claimedAt);
        }
    }

    // ready + 15분 경과: SUCCESS_PAYMENT가 아니면 EXPIRED, 실패 상태면 FAILED (6-2절 10번)
    private void recoverReady(Payment payment, OffsetDateTime claimedAt) {
        lookup(payment).ifPresent(order -> {
            PaymentStatus next;
            if (SUCCESS.equals(order.status())) {
                // approve 없이 승인될 수 없으므로 자동 판정하지 않는다.
                log.error("KakaoPay order succeeded without approval: orderId={}", payment.getId());
                next = PaymentStatus.NEEDS_REVIEW;
            } else if (FAILED.contains(order.status())) {
                next = PaymentStatus.FAILED;
            } else {
                next = PaymentStatus.EXPIRED;
            }
            stateService.transition(payment.getId(), PaymentStatus.READY, claimedAt, next);
        });
    }

    /** approve가 4xx로 거부되면 즉시 주문 조회로 판정한다(6-2절 12번). */
    public void resolveApproveRejection(Payment payment, OffsetDateTime claimedAt) {
        lookup(payment).ifPresent(order -> resolveApproving(payment, claimedAt, order));
    }

    // 주문 조회 status를 APPROVING 주문의 다음 상태로 옮긴다(6-2절 11번).
    private void resolveApproving(Payment payment, OffsetDateTime claimedAt, KakaoPayClient.OrderResponse order) {
        String status = order.status();
        if (SUCCESS.equals(status)) {
            stateService.completeApproval(payment, claimedAt, new PaymentStateService.ApprovalResult(
                    actionAid(order, "PAYMENT"),
                    order.tid(),
                    order.cid(),
                    order.partnerOrderId(),
                    order.partnerUserId(),
                    total(order.amount()),
                    order.paymentMethodType(),
                    KakaoPayClient.toOffsetDateTime(order.approvedAt())
            ));
        } else if (FAILED.contains(status)) {
            stateService.transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.FAILED);
        } else if (CANCELED.equals(status) || PART_CANCELED.equals(status)) {
            // 승인 반영 전 취소·부분 취소는 이번 범위에서 발생하지 않아야 하므로 수동 확인 대상이다.
            log.error("KakaoPay order canceled before internal approval: orderId={}, status={}",
                    payment.getId(), status);
            stateService.transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.NEEDS_REVIEW);
        } else if (payment.isReadyExpired(PaymentStateService.now())) {
            // ready + 15분이 지났는데도 진행 중이면 결제창이 만료된 것이다.
            stateService.transition(payment.getId(), PaymentStatus.APPROVING, claimedAt, PaymentStatus.EXPIRED);
        }
        // 그 밖의 진행 중 status는 APPROVING을 유지하고 다음 복구로 넘긴다.
    }

    /**
     * 외부 결제 취소 (6-6절 결제 취소 3번). 내부 회수는 이미 CANCELING 전환과 함께 반영돼 있다.
     * 성공하면 CANCELED, 결과 불명이면 CANCELING 유지, 4xx면 즉시 주문 조회로 판정한다.
     */
    public void requestCancel(Payment payment, OffsetDateTime claimedAt) {
        KakaoPayClient.CancelResponse response;
        try {
            response = kakaoPayClient.cancel(new KakaoPayClient.CancelRequest(
                    payment.getCid(),
                    payment.getTid(),
                    payment.getAmount(),
                    0,
                    // 카카오페이 쪽 잔여 금액이 다르면 거부되게 해 이중 취소를 막는다.
                    payment.getAmount()
            ));
        } catch (KakaoPayException e) {
            switch (e.getKind()) {
                case CONFIGURATION -> throw e;
                case REJECTED -> lookup(payment).ifPresent(order -> resolveCancelRejection(payment, claimedAt, order));
                case UNKNOWN -> {
                }
                default -> throw new IllegalStateException("Unhandled KakaoPay failure kind: " + e.getKind());
            }
            return;
        }
        stateService.completeCancel(payment, claimedAt, new PaymentStateService.CancelResult(
                response.aid(),
                response.tid(),
                response.cid(),
                CANCELED.equals(response.status()) ? total(response.approvedCancelAmount()) : null,
                KakaoPayClient.toOffsetDateTime(response.canceledAt())
        ));
    }

    // 취소 4xx 후 조회: 이미 취소됐으면(-785 중복 등) CANCELED, 결제가 살아 있으면 수동 확인 대상이다.
    private void resolveCancelRejection(Payment payment, OffsetDateTime claimedAt, KakaoPayClient.OrderResponse order) {
        if (CANCELED.equals(order.status())) {
            completeCancelFromOrder(payment, claimedAt, order);
            return;
        }
        log.error("KakaoPay cancel rejected and order is not canceled: orderId={}, status={}",
                payment.getId(), order.status());
        stateService.transition(payment.getId(), PaymentStatus.CANCELING, claimedAt, PaymentStatus.NEEDS_REVIEW);
    }

    // 오래된 CANCELING: 취소가 반영됐으면 CANCELED, 아직 결제 상태면 확정 응답을 못 받은 것이므로 다시 취소한다.
    private void resolveCanceling(Payment payment, OffsetDateTime claimedAt, KakaoPayClient.OrderResponse order) {
        if (CANCELED.equals(order.status())) {
            completeCancelFromOrder(payment, claimedAt, order);
        } else if (SUCCESS.equals(order.status())) {
            requestCancel(payment, claimedAt);
        } else {
            log.error("KakaoPay order in unexpected status while canceling: orderId={}, status={}",
                    payment.getId(), order.status());
            stateService.transition(payment.getId(), PaymentStatus.CANCELING, claimedAt, PaymentStatus.NEEDS_REVIEW);
        }
    }

    private void completeCancelFromOrder(Payment payment, OffsetDateTime claimedAt, KakaoPayClient.OrderResponse order) {
        stateService.completeCancel(payment, claimedAt, new PaymentStateService.CancelResult(
                actionAid(order, "CANCEL"),
                order.tid(),
                order.cid(),
                total(order.canceledAmount()),
                KakaoPayClient.toOffsetDateTime(order.canceledAt())
        ));
    }

    // 조회 실패(타임아웃·5xx·4xx)는 상태를 유지한다. 설정 오류만 호출한 쪽으로 던진다.
    private Optional<KakaoPayClient.OrderResponse> lookup(Payment payment) {
        try {
            return Optional.ofNullable(kakaoPayClient.order(new KakaoPayClient.OrderRequest(payment.getCid(), payment.getTid())));
        } catch (KakaoPayException e) {
            if (e.getKind() == KakaoPayException.Kind.CONFIGURATION) {
                throw e;
            }
            return Optional.empty();
        }
    }

    private static String actionAid(KakaoPayClient.OrderResponse order, String actionType) {
        if (order.paymentActionDetails() == null) {
            return null;
        }
        return order.paymentActionDetails().stream()
                .filter(detail -> actionType.equals(detail.paymentActionType()))
                .map(KakaoPayClient.PaymentActionDetail::aid)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static Long total(KakaoPayClient.Amount amount) {
        return amount == null ? null : amount.total();
    }
}
