package com.gamerin.backend.domain.payment.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;

// TID·AID·CID·URL은 내려주지 않는다(04 문서 6-6절).
public record PaymentResponse(
        UUID orderId,
        PaymentStatus status,
        PaymentPurpose purpose,
        String productCode,
        String itemName,
        Long amount,
        String paymentMethodType,
        OffsetDateTime createdAt,
        OffsetDateTime approvedAt,
        OffsetDateTime canceledAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getPurpose(),
                payment.getProductCode().name(),
                payment.getItemName(),
                payment.getAmount(),
                payment.getPaymentMethodType(),
                payment.getCreatedAt(),
                payment.getApprovedAt(),
                payment.getCanceledAt()
        );
    }
}
