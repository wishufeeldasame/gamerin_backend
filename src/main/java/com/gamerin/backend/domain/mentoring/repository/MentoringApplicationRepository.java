package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;

public interface MentoringApplicationRepository extends JpaRepository<MentoringApplication, UUID> {

    
} 
