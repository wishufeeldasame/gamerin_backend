package com.gamerin.backend.domain.auth.repository;

import com.gamerin.backend.domain.auth.entity.SocialSignupSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;



public interface SocialSignupSessionRepository extends JpaRepository<SocialSignupSession, UUID>{
    Optional<SocialSignupSession> findBySignupTokenHash(String signupTokenHash);
}
