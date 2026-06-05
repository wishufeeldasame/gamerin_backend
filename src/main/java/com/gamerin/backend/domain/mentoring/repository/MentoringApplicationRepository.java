package com.gamerin.backend.domain.mentoring.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;

public interface MentoringApplicationRepository extends JpaRepository<MentoringApplication, UUID> {

    // 멘티 ID로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByMenteeId(UUID menteeId, Pageable pageable);

    // 멘토 ID(program.mentor.id)로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByProgramMentorId(UUID mentorId, Pageable pageable);

    boolean existsByMenteeIdAndProgramIdAndStatusIn(UUID menteeId, UUID programId, List<ApplicationStatus> statuses);

    List<MentoringApplication> findByStatusAndUpdatedAtBefore(ApplicationStatus status, OffsetDateTime dateTime);
    
} 
