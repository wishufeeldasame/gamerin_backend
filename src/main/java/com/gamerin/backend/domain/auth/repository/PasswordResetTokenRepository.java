package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);
    List<PasswordResetToken> findAllByUserIdAndUsedAtIsNull(UUID userId);
}
