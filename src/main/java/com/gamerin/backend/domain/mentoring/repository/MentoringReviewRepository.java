package com.gamerin.backend.domain.mentoring.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.MentoringReview;

public interface MentoringReviewRepository extends JpaRepository<MentoringReview, UUID>{

    // 특정 신청 건에 대해 리뷰가 이미 존재하는지 확인
    boolean existsByApplicationId(UUID applicationId);

    @Query("select r.application.id from MentoringReview r where r.application.id in :applicationIds")
    List<UUID> findReviewedApplicationIds(@Param("applicationIds") List<UUID> applicationIds);

    // 특정 멘토의 리뷰 목록 조회 (페이징)
    Page<MentoringReview> findByMentorUserId(UUID mentorId, Pageable pageable);
}
