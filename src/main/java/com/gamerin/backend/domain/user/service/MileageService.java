package com.gamerin.backend.domain.user.service;

import java.util.UUID;

import com.gamerin.backend.domain.user.dto.response.MileageTransactionResponse;
import com.gamerin.backend.domain.user.dto.response.MyMileageResponse;
import com.gamerin.backend.domain.user.entity.MileageTransaction;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.MileageTransactionRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MileageService {

    private final MileageWalletRepository walletRepository;
    private final MileageTransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public MileageService(MileageWalletRepository walletRepository,
                          MileageTransactionRepository transactionRepository,
                          UserRepository userRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    /**
     * 마일리지 차감 (비관적 락을 적용하여 잔액 동시 차감 시 Race Condition 방지)
     */
    @Transactional
    public void useMileage(User user, Long amount, TransactionType type, String description, UUID referenceId) {
        MileageWallet wallet = getOrCreateWalletForUpdate(user);

        // 1. 잔액 차감
        wallet.deduct(amount);

        // 2. 트랜잭션 로그 기록
        saveTransaction(user, -amount, wallet.getBalance(), type, description, referenceId);
    }

    /**
     * 마일리지 지급/정산/환불 (비관적 락을 적용하여 동시 정산 시 덮어쓰기/잔액 유실 방지)
     */
    @Transactional
    public void addMileage(User user, Long amount, TransactionType type, String description, UUID referenceId) {
        MileageWallet wallet = getOrCreateWalletForUpdate(user);

        // 1. 잔액 추가
        wallet.addBalance(amount);

        // 2. 트랜잭션 로그 기록
        saveTransaction(user, amount, wallet.getBalance(), type, description, referenceId);
    }

    private void saveTransaction(User user, Long amount, Long balanceAfter,
                                 TransactionType type, String description, UUID referenceId) {
        MileageTransaction transaction = MileageTransaction.builder()
                .user(user)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .type(type)
                .description(description)
                .referenceId(referenceId)
                .build();
        transactionRepository.save(transaction);
    }

    /**
     * 비관적 쓰기 락(SELECT FOR UPDATE)을 걸고 지갑 조회 (잔액 변경용)
     * - 지갑 미존재 시 동시 생성 충돌(PK Unique Violation)을 방지하기 위해 User 락을 활용한 Double-Checked Locking 적용
     */
    public MileageWallet getOrCreateWalletForUpdate(User user) {
        return walletRepository.findByUserIdForUpdate(user.getId())
                .orElseGet(() -> {
                    // 지갑이 없을 때만 User 행 락을 획득하여 동시 생성 직렬화
                    userRepository.findActiveByIdForUpdate(user.getId());
                    return walletRepository.findByUserIdForUpdate(user.getId())
                            .orElseGet(() -> {
                                createInitialWallet(user);
                                return walletRepository.findByUserIdForUpdate(user.getId())
                                        .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다."));
                            });
                });
    }

    /**
     * 기본 지갑 조회 및 미존재 시 생성 (단순 잔액 조회 등 읽기 전용 작업에서 재사용)
     */
    public MileageWallet getOrCreateWallet(User user) {
        return walletRepository.findById(user.getId())
                .orElseGet(() -> {
                    userRepository.findActiveByIdForUpdate(user.getId());
                    return walletRepository.findById(user.getId())
                            .orElseGet(() -> createInitialWallet(user));
                });
    }

    private MileageWallet createInitialWallet(User user) {
        MileageWallet newWallet = new MileageWallet();
        User managedUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        newWallet.setUser(managedUser);
        newWallet.setBalance(0L);
        return walletRepository.saveAndFlush(newWallet);
    }

    // 잔액만 조회 (락 없이 빠른 읽기)
    @Transactional(readOnly = true)
    public MyMileageResponse getMyBalance(User user) {
        MileageWallet wallet = getOrCreateWallet(user);
        return new MyMileageResponse(wallet.getBalance());
    }

    // 트랜잭션 내역만 조회(페이징 적용)
    @Transactional(readOnly = true)
    public Page<MileageTransactionResponse> getMyTransactions(User user, Pageable pageable) {
        return transactionRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)
                .map(MileageTransactionResponse::from);
    }

    // 마일리지 충전 (비관적 락으로 안전하게 잔액 증액)
    @Transactional
    public MyMileageResponse chargeMileage(User user, Long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0원보다 커야 합니다.");
        }

        // 마일리지 추가 및 트랜잭션 기록
        addMileage(user, amount, TransactionType.CHARGE, "테스트용 가상 충전", null);

        // 변경된 잔액 정보 반환
        return getMyBalance(user);
    }
}
