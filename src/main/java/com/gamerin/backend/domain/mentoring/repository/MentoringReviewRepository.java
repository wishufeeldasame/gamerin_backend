package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mentoring.entity.MentoringReview;

public interface MentoringReviewRepository extends JpaRepository<MentoringReview, UUID>{

    
}