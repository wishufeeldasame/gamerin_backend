package com.gamerin.backend.domain.payment.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;

// TID는 내려주지 않는다. 결제창 URL은 READY이고 15분 이내일 때만 내려준다(04 문서 6-4·6-6절).
public record PaymentReadyResponse(
        UUID orderId,
        PaymentStatus status,
        String pcUrl,
        String mobileUrl
) {
    public static PaymentReadyResponse from(Payment payment, OffsetDateTime now) {
        boolean urlAvailable = payment.getStatus() == PaymentStatus.READY && !payment.isReadyExpired(now);
        return new PaymentReadyResponse(
                payment.getId(),
                payment.getStatus(),
                urlAvailable ? payment.getNextRedirectPcUrl() : null,
                urlAvailable ? payment.getNextRedirectMobileUrl() : null
        );
    }

    @Override
    public String toString() {
        // 결제창 URL을 로그에 남기지 않는다.
        return "PaymentReadyResponse[orderId=" + orderId + ", status=" + status + "]";
    }
}
