package com.gamerin.backend.domain.payment.dto.response;

import com.gamerin.backend.domain.payment.entity.PaymentPurpose;
import com.gamerin.backend.domain.payment.model.PaymentProduct;

public record PaymentProductResponse(
        String productCode,
        PaymentPurpose purpose,
        String name,
        long price,
        Long mileage,
        Integer durationDays
) {
    public static PaymentProductResponse from(PaymentProduct product) {
        return new PaymentProductResponse(
                product.name(),
                product.getPurpose(),
                product.getDisplayName(),
                product.getPrice(),
                product.getMileage(),
                product.getDurationDays()
        );
    }
}
