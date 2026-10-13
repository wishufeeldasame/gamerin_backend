package com.gamerin.backend.domain.mentoring.service;

import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;

/**
 * 멘토링 정산 수수료 계산 공통 컴포넌트 (KP-C1 / #90 F3)
 *
 * 공식:
 * - 수수료(fee) = floor(appliedMileage * feeRateBp / 10000)
 * - 멘토 지급액(payout) = appliedMileage - fee
 */
public final class MentoringSettlementCalculator {

    public static final int BASIS_POINTS_DIVISOR = 10000;
    public static final int DEFAULT_FEE_RATE_BP = 1000; // 기본 10% (1000bp)

    private MentoringSettlementCalculator() {
        // 인스턴스화 방지
    }

    /**
     * 계산 결과 레코드
     * @param feeAmount 수수료 금액 (플랫폼 귀속)
     * @param payoutAmount 멘토에게 실제 지급되는 정산 마일리지
     */
    public record SettlementResult(long feeAmount, long payoutAmount) {}

    /**
     * 신청 마일리지와 수수료 요율(bp)로 정산 금액 및 수수료를 계산합니다.
     */
    public static SettlementResult calculate(long appliedMileage, int feeRateBp) {
        if (appliedMileage < 0) {
            throw new IllegalArgumentException("신청 마일리지는 0 이상이어야 합니다. 입력값: " + appliedMileage);
        }
        if (feeRateBp < 0 || feeRateBp > BASIS_POINTS_DIVISOR) {
            throw new IllegalArgumentException("수수료 요율(bp)은 0 이상 " + BASIS_POINTS_DIVISOR + " 이하여야 합니다. 입력값: " + feeRateBp);
        }

        // 곱셈 오버플로 방지 및 검증
        long product;
        try {
            product = Math.multiplyExact(appliedMileage, (long) feeRateBp);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("수수료 계산 중 오버플로가 발생했습니다: appliedMileage=" + appliedMileage + ", feeRateBp=" + feeRateBp);
        }

        // 정수 나눗셈은 바닥 함수(floor)로 동작함
        long feeAmount = product / BASIS_POINTS_DIVISOR;
        long payoutAmount = appliedMileage - feeAmount;

        return new SettlementResult(feeAmount, payoutAmount);
    }

    /**
     * 신청 엔티티의 정보로 수수료를 계산하고, fee_amount 필드를 설정한 후 결과를 반환합니다.
     */
    public static SettlementResult applySettlement(MentoringApplication application) {
        if (application == null) {
            throw new IllegalArgumentException("신청 엔티티는 null일 수 없습니다.");
        }
        int feeRateBp = (application.getFeeRateBp() != null) ? application.getFeeRateBp() : DEFAULT_FEE_RATE_BP;
        long appliedMileage = (application.getAppliedMileage() != null) ? application.getAppliedMileage() : 0L;

        SettlementResult result = calculate(appliedMileage, feeRateBp);
        application.setFeeAmount(result.feeAmount());
        return result;
    }
}