package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mentoring.entity.MentorProfile;

public interface MentorProfileRepository extends JpaRepository<MentorProfile, UUID>{

    
} 
