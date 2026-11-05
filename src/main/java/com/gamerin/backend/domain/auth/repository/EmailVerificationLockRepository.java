package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.EmailVerificationLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface EmailVerificationLockRepository extends JpaRepository<EmailVerificationLock, String> {
    // 같은 이메일의 발송 요청을 직렬화하기 위해 잠금 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationLock> findByEmail(String email);
}
