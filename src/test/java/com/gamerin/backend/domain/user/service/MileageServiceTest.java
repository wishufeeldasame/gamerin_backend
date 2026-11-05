package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamerin.backend.domain.user.dto.response.MyMileageResponse;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.MileageTransactionRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class MileageServiceTest {

    @Mock
    private MileageWalletRepository walletRepository;
    @Mock
    private MileageTransactionRepository transactionRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MileageService mileageService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = User.createLocal("test@test.com", "testuser", "테스터", "encoded_pw");
        // Reflection 또는 User 생성 규격에 맞게 ID 설정 (testUser.getId() 기준)
        org.springframework.test.util.ReflectionTestUtils.setField(testUser, "id", userId);
    }

    @Test
    @DisplayName("지갑이 없는 신규 유저가 잔액 조회 시 INSERT/LOCK 없이 0원을 반환한다")
    void getMyBalance_whenWalletDoesNotExist_returnsZeroWithoutCreation() {
        // given: 지갑이 존재하지 않는 상태
        given(walletRepository.findById(userId)).willReturn(Optional.empty());

        // when
        MyMileageResponse response = mileageService.getMyBalance(testUser);

        // then
        assertThat(response.currentBalance()).isEqualTo(0L);

        // 지갑 생성이나 사용자 락 조회가 호출되지 않았는지 검증
        verify(walletRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(walletRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(userRepository, never()).findActiveByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("지갑이 있는 유저가 잔액 조회 시 보유한 마일리지를 정상 반환한다")
    void getMyBalance_whenWalletExists_returnsCurrentBalance() {
        // given: 5,000원 잔액을 가진 지갑 존재
        MileageWallet wallet = new MileageWallet();
        wallet.setUser(testUser);
        wallet.setBalance(5000L);
        given(walletRepository.findById(userId)).willReturn(Optional.of(wallet));

        // when
        MyMileageResponse response = mileageService.getMyBalance(testUser);

        // then
        assertThat(response.currentBalance()).isEqualTo(5000L);
    }
}