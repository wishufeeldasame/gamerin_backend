package com.gamerin.backend.domain.user.service;

import com.gamerin.backend.domain.user.dto.response.MileageTransactionResponse;
import com.gamerin.backend.domain.user.dto.response.MyMileageResponse;
import com.gamerin.backend.domain.user.entity.MileageTransaction;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.MileageTransactionRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

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

    @Transactional
    public void useMileage(User user, Long amount, TransactionType type, String description, UUID referenceId) {
        MileageWallet wallet = getOrCreateWallet(user);
        
        // 1. 잔액 차감 (기존 MileageWallet의 deduct 로직 활용)
        wallet.deduct(amount);
        
        // 2. 트랜잭션 로그 기록
        saveTransaction(user, -amount, wallet.getBalance(), type, description, referenceId);
    }

    @Transactional
    public void addMileage(User user, Long amount, TransactionType type, String description, UUID referenceId) {
        MileageWallet wallet = getOrCreateWallet(user);
        
        // 1. 잔액 추가
        wallet.addBalance(amount);
        
        // 2. 트랜잭션 로그 기록
        saveTransaction(user, amount, wallet.getBalance(), type, description, referenceId);
    }

    private void saveTransaction(User user, Long amount, Long balanceAfter, TransactionType type, String description, UUID referenceId) {
        // 이전 단계에서 직접 구현한 정적 빌더 패턴을 그대로 사용할 수 있습니다.
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

    public MileageWallet getOrCreateWallet(User user) {
        // 이미 지갑이 있는지 먼저 확인
        return walletRepository.findById(user.getId())
                .orElseGet(() -> {
                    MileageWallet newWallet = new MileageWallet();

                    User managedUser = userRepository.findById(user.getId())
                            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

                    newWallet.setUser(managedUser);
                    newWallet.setBalance(0L);

                    return walletRepository.save(newWallet);
                });
    }

    // 잔액만 조회
    @Transactional(readOnly = true)
    public MyMileageResponse getMyBalance(User user) {
        MileageWallet wallet = getOrCreateWallet(user);

        return new MyMileageResponse(wallet.getBalance());
    }

    // 트랜잭션 내역만 조회(페이징 적용)
    @Transactional(readOnly = true)
    public Page<MileageTransactionResponse> getMyTransactions(User user, Pageable pageable) {
        return transactionRepository.findAllByUserOrderByCreatedAtDesc(user, pageable).map(MileageTransactionResponse::from);

    }

    // 마일리지 추가하고 로그 남기는
    @Transactional
    public MyMileageResponse chargeMileage(User user, Long amount) {
        if (amount <= 0) {
            throw new RuntimeException("충전 금액은 0원보다 커야 합니다.");
        }

        // 마일리지 추가 및 트랜잭션 기록
        addMileage(user, amount, TransactionType.CHARGE, "테스트용 가상 충전", null);

        // 변경된 잔액 정보 반환
        return getMyBalance(user);
    }
    

                        

    
}