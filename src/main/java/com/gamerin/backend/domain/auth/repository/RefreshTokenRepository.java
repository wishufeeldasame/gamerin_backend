package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(UUID userId);
}
