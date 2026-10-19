package com.gamerin.backend.domain.payment.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentApproveRequest(
        @NotNull UUID orderId,
        @NotBlank String pgToken
) {
    @Override
    public String toString() {
        // pg_token을 로그에 남기지 않는다.
        return "PaymentApproveRequest[orderId=" + orderId + "]";
    }
}
