package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;

import jakarta.persistence.LockModeType;

public interface MentorProfileRepository extends JpaRepository<MentorProfile, UUID>{

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mentor from MentorProfile mentor where mentor.userId = :userId")
    java.util.Optional<MentorProfile> findByIdForUpdate(@Param("userId") UUID userId);
} 
