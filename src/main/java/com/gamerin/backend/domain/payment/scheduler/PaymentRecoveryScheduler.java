package com.gamerin.backend.domain.payment.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamerin.backend.domain.payment.service.PaymentRecoveryService;

// 사용자가 돌아오지 않아도 미확정 주문을 6-6절 규칙대로 정리한다.
@Component
public class PaymentRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryScheduler.class);

    private final PaymentRecoveryService recoveryService;

    public PaymentRecoveryScheduler(PaymentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(
            initialDelayString = "${app.payment.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${app.payment.recovery.fixed-delay-ms:300000}"
    )
    public void recoverStalePayments() {
        try {
            int processed = recoveryService.recoverStalePayments();
            if (processed > 0) {
                log.info("Payment recovery processed {} orders", processed);
            }
        } catch (RuntimeException e) {
            log.error("Payment recovery run failed: {}", e.getClass().getSimpleName());
        }
    }
}
