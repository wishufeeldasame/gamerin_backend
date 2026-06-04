package com.gamerin.backend.domain.user.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.MileageTransaction;
import com.gamerin.backend.domain.user.entity.TransactionType;

public record MileageTransactionResponse(
        UUID id,
        Long amount,
        Long balanceAfter,
        TransactionType type,
        String typeDescription,
        String description,
        OffsetDateTime createdAt
) {
    public static MileageTransactionResponse from(MileageTransaction transaction) {
        return new MileageTransactionResponse(
            transaction.getId(),
            transaction.getAmount(),
            transaction.getBalanceAfter(),
            transaction.getType(),
            transaction.getType().getDescription(),
            transaction.getDescription(),
            transaction.getCreatedAt()
        );

    }
}