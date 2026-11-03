package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserConsentRepository extends JpaRepository<UserConsent, UUID> {
    List<UserConsent> findAllByUserId(UUID userId);
}
