package com.gamerin.backend.domain.user.repository;


import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.user.entity.MileageTransaction;
import com.gamerin.backend.domain.user.entity.User;

public interface MileageTransactionRepository extends JpaRepository<MileageTransaction, UUID>{
    Page<MileageTransaction> findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable);
 
} 
