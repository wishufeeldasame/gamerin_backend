package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.EmailVerification;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
    // 동시 확인 요청이 시도 횟수 증가를 덮어쓰지 않도록 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findTopByEmailOrderByCreatedAtDesc(String email);

    Optional<EmailVerification> findByVerificationTokenHashAndConsumedAtIsNull(String verificationTokenHash);
}
