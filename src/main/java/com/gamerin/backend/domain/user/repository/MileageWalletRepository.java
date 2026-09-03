package com.gamerin.backend.domain.user.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.user.entity.MileageWallet;

import jakarta.persistence.LockModeType;

public interface MileageWalletRepository extends JpaRepository<MileageWallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from MileageWallet wallet where wallet.userId = :userId")
    java.util.Optional<MileageWallet> findByIdForUpdate(@Param("userId") UUID userId);
}
