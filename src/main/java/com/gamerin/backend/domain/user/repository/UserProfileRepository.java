package com.gamerin.backend.domain.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.user.entity.UserProfile;

import jakarta.persistence.LockModeType;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserProfile p where p.userId = :userId")
    Optional<UserProfile> findByUserIdForUpdate(@Param("userId") UUID userId);
}
