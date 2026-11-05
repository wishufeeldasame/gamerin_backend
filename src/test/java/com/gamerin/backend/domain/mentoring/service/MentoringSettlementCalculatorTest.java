package com.gamerin.backend.domain.mentoring.service;

import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MentoringSettlementCalculatorTest {

    @Test
    @DisplayName("기본 요율 10%(1000bp)일 때 수수료와 지급액이 정확히 계산된다")
    void calculateDefaultFeeRate() {
        // 10,000 P -> 수수료 1,000 P, 지급액 9,000 P
        MentoringSettlementCalculator.SettlementResult result1 = MentoringSettlementCalculator.calculate(10000L, 1000);
        assertThat(result1.feeAmount()).isEqualTo(1000L);
        assertThat(result1.payoutAmount()).isEqualTo(9000L);

        // 30,000 P -> 수수료 3,000 P, 지급액 27,000 P
        MentoringSettlementCalculator.SettlementResult result2 = MentoringSettlementCalculator.calculate(30000L, 1000);
        assertThat(result2.feeAmount()).isEqualTo(3000L);
        assertThat(result2.payoutAmount()).isEqualTo(27000L);
    }

    @Test
    @DisplayName("바닥 함수(floor) 절삭 검증: 나누어 떨어지지 않는 경우 내림 처리된다")
    void calculateFloorRounding() {
        // 9,999 P * 1000 / 10000 = 999.9 -> 999 P
        MentoringSettlementCalculator.SettlementResult result = MentoringSettlementCalculator.calculate(9999L, 1000);
        assertThat(result.feeAmount()).isEqualTo(999L);
        assertThat(result.payoutAmount()).isEqualTo(9000L); // 9999 - 999 = 9000
    }

    @Test
    @DisplayName("0원 무료 멘토링 프로그램일 때 수수료와 지급액은 모두 0원이다")
    void calculateZeroMileage() {
        MentoringSettlementCalculator.SettlementResult result = MentoringSettlementCalculator.calculate(0L, 1000);
        assertThat(result.feeAmount()).isEqualTo(0L);
        assertThat(result.payoutAmount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("수수료 요율이 0bp(0%)이거나 10000bp(100%)일 때의 경계값 검증")
    void calculateBoundaryFeeRates() {
        // 0% 수수료
        MentoringSettlementCalculator.SettlementResult freeResult = MentoringSettlementCalculator.calculate(10000L, 0);
        assertThat(freeResult.feeAmount()).isEqualTo(0L);
        assertThat(freeResult.payoutAmount()).isEqualTo(10000L);

        // 100% 수수료
        MentoringSettlementCalculator.SettlementResult fullFeeResult = MentoringSettlementCalculator.calculate(10000L,
                10000);
        assertThat(fullFeeResult.feeAmount()).isEqualTo(10000L);
        assertThat(fullFeeResult.payoutAmount()).isEqualTo(0L);
    }

    @Test
        @DisplayName("곱셈 오버플로 경계 검증: Long.MAX_VALUE에 가까운 금액 계산 시 오버플로를 안전하게 차단한다")
        void calculateMultiplicationOverflowThrowsException() {
            // Long.MAX_VALUE * 1000 은 64비트 Long 범위를 초과하므로 ArithmeticException 발생
            assertThatThrownBy(() -> MentoringSettlementCalculator.calculate(Long.MAX_VALUE, 1000))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("수수료 계산 중 오버플로가 발생했습니다");
    
            // 오버플로가 발생하지 않는 상한 경계값 검증
            long safeMax = Long.MAX_VALUE / 1000;
            MentoringSettlementCalculator.SettlementResult safeResult =
                    MentoringSettlementCalculator.calculate(safeMax, 1000);
            assertThat(safeResult.feeAmount()).isEqualTo(safeMax / 10);
            assertThat(safeResult.payoutAmount()).isEqualTo(safeMax - safeResult.feeAmount());
        }

    @Test
    @DisplayName("음수 마일리지나 유효하지 않은 bp 요율은 IllegalArgumentException이 발생한다")
    void calculateInvalidInputsThrowException() {
        assertThatThrownBy(() -> MentoringSettlementCalculator.calculate(-1L, 1000))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MentoringSettlementCalculator.calculate(10000L, -1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> MentoringSettlementCalculator.calculate(10000L, 10001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("엔티티 적용 메서드(applySettlement)는 fee_amount를 저장하고 동일한 결과를 반환한다")
    void applySettlementSetsFeeAmountOnApplication() {
        MentoringApplication application = new MentoringApplication();
        application.setAppliedMileage(50000L);
        application.setFeeRateBp(1000);

        MentoringSettlementCalculator.SettlementResult result = MentoringSettlementCalculator
                .applySettlement(application);

        assertThat(result.feeAmount()).isEqualTo(5000L);
        assertThat(result.payoutAmount()).isEqualTo(45000L);
        assertThat(application.getFeeAmount()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("신청 엔티티의 appliedMileage 또는 feeRateBp가 null이면 조용히 0원을 지급하지 않고 IllegalStateException을 던진다")
    void applySettlementThrowsExceptionWhenRequiredFieldsAreNull() {
        MentoringApplication nullApplied = new MentoringApplication();
        nullApplied.setAppliedMileage(null);
        nullApplied.setFeeRateBp(1000);

        assertThatThrownBy(() -> MentoringSettlementCalculator.applySettlement(nullApplied))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("신청 마일리지 또는 수수료 요율이 비어 있습니다");

        MentoringApplication nullBp = new MentoringApplication();
        nullBp.setAppliedMileage(10000L);
        nullBp.setFeeRateBp(null);

        assertThatThrownBy(() -> MentoringSettlementCalculator.applySettlement(nullBp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("신청 마일리지 또는 수수료 요율이 비어 있습니다");
    }
}