package com.gamerin.backend.domain.user.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.user.entity.MileageWallet;

public interface MileageWalletRepository extends JpaRepository<MileageWallet, UUID> {

    
} 