package com.gamerin.backend.domain.user.entity;

public enum TransactionType {
    CHARGE(" + 충전"),
    MENTORING_PAY(" - 멘토링 결제"),
    MENTORING_REFUND(" + 멘토링 환불"),
    SETTLEMENT(" + 멘토링 정산 수입"),
    WITHDRAW(" + 출금");

    private final String description;

    TransactionType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}