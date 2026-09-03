package com.gamerin.backend.domain.mentoring.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;

import jakarta.persistence.LockModeType;

public interface MentoringApplicationRepository extends JpaRepository<MentoringApplication, UUID> {

    // 멘티 ID로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByMenteeId(UUID menteeId, Pageable pageable);

    // 멘토 ID(program.mentor.id)로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByProgramMentorId(UUID mentorId, Pageable pageable);

    boolean existsByMenteeIdAndProgramIdAndStatusIn(UUID menteeId, UUID programId, List<ApplicationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select application from MentoringApplication application where application.id = :id")
    java.util.Optional<MentoringApplication> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        select application.id
        from MentoringApplication application
        where application.status = :status
          and application.updatedAt < :threshold
        order by application.updatedAt asc, application.id asc
        """)
    List<UUID> findIdsByStatusAndUpdatedAtBefore(
            @Param("status") ApplicationStatus status,
            @Param("threshold") OffsetDateTime threshold
    );
    
} 
