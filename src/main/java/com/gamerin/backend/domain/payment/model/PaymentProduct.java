package com.gamerin.backend.domain.payment.model;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.payment.entity.PaymentPurpose;

/**
 * 서버 상품 정의 (04 문서 6-1절, 06 문서 M1, 07 문서 11-4절).
 * 상품 목록 응답·ready 가격 결정·멤버십 기간이 모두 이 정의 하나를 사용한다.
 * 충전은 결제 금액 1원당 1마일리지이며 보너스는 없다.
 */
public enum PaymentProduct {
    CHARGE_5000(PaymentPurpose.MILEAGE_CHARGE, "마일리지 5,000", 5_000L, null),
    CHARGE_10000(PaymentPurpose.MILEAGE_CHARGE, "마일리지 10,000", 10_000L, null),
    CHARGE_30000(PaymentPurpose.MILEAGE_CHARGE, "마일리지 30,000", 30_000L, null),
    CHARGE_50000(PaymentPurpose.MILEAGE_CHARGE, "마일리지 50,000", 50_000L, null),
    MEMBERSHIP_30D(PaymentPurpose.MEMBERSHIP_PASS, "멤버십 30일 이용권", 4_900L, 30);

    private final PaymentPurpose purpose;
    private final String displayName;
    private final long price;
    private final Integer durationDays;

    PaymentProduct(PaymentPurpose purpose, String displayName, long price, Integer durationDays) {
        this.purpose = purpose;
        this.displayName = displayName;
        this.price = price;
        this.durationDays = durationDays;
    }

    public static PaymentProduct fromCode(String code) {
        return Arrays.stream(values())
                .filter(product -> product.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용하지 않은 상품 코드입니다."));
    }

    public PaymentPurpose getPurpose() {
        return purpose;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getPrice() {
        return price;
    }

    // 충전 상품만 값이 있다.
    public Long getMileage() {
        return purpose == PaymentPurpose.MILEAGE_CHARGE ? price : null;
    }

    // 이용권 상품만 값이 있다.
    public Integer getDurationDays() {
        return durationDays;
    }
}
