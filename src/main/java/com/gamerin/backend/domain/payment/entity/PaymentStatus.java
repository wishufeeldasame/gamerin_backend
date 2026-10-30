package com.gamerin.backend.domain.payment.entity;

// 04 문서 6-6절의 9개 상태. V35의 chk_payments_status와 같은 목록을 유지한다.
public enum PaymentStatus {
    CREATED,
    READY,
    APPROVING,
    APPROVED,
    CANCELING,
    CANCELED,
    FAILED,
    EXPIRED,
    NEEDS_REVIEW
}
