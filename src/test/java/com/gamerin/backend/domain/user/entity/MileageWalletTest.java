package com.gamerin.backend.domain.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MileageWalletTest {

    @Test
    @DisplayName("음수 금액으로 deduct를 호출하면 IllegalArgumentException이 발생하고 잔액은 변경되지 않는다")
    void deductRejectsNegativeAmountWithoutChangingBalance() {
        MileageWallet wallet = new MileageWallet();
        wallet.setBalance(1000L);

        assertThatThrownBy(() -> wallet.deduct(-1000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("차감할 마일리지는 0 이상이어야 합니다.");

        assertThat(wallet.getBalance()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("0원으로 deduct를 호출하면 예외 없이 잔액이 유지된다")
    void deductZeroMaintainsBalance() {
        MileageWallet wallet = new MileageWallet();
        wallet.setBalance(1000L);

        wallet.deduct(0L);

        assertThat(wallet.getBalance()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("잔액보다 큰 금액으로 deduct를 호출하면 RuntimeException이 발생한다")
    void deductThrowsWhenInsufficientBalance() {
        MileageWallet wallet = new MileageWallet();
        wallet.setBalance(500L);

        assertThatThrownBy(() -> wallet.deduct(1000L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("마일리지가 부족합니다");
    }

    @Test
    @DisplayName("음수 금액으로 addBalance를 호출하면 IllegalArgumentException이 발생하고 잔액은 변경되지 않는다")
    void addBalanceRejectsNegativeAmount() {
        MileageWallet wallet = new MileageWallet();
        wallet.setBalance(1000L);

        assertThatThrownBy(() -> wallet.addBalance(-500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("충전/지급 금액은 0 이상이어야 합니다.");

        assertThat(wallet.getBalance()).isEqualTo(1000L);
    }
}