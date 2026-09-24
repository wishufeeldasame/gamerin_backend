package com.gamerin.backend.domain.user.repository;

import com.gamerin.backend.domain.user.entity.MileageWallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

public interface MileageWalletRepository extends JpaRepository<MileageWallet, UUID> {

    // 지갑 잔액 동시 갱신 시 덮어쓰기(Lost Update) 방지를 위한 비관적 쓰기 락 단건 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from MileageWallet w where w.userId = :userId")
    Optional<MileageWallet> findByUserIdForUpdate(@Param("userId") UUID userId);
}
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from MileageWallet wallet where wallet.userId = :userId")
    java.util.Optional<MileageWallet> findByIdForUpdate(@Param("userId") UUID userId);
}
