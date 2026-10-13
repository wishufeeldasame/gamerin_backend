package com.gamerin.backend.domain.payment.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// requestId는 구매자와 함께 중복 주문을 막는 키다(04 문서 6-1절).
public record PaymentReadyRequest(
        @NotBlank String productCode,
        @NotNull UUID requestId
) {
}
