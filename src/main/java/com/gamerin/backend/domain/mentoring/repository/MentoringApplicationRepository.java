package com.gamerin.backend.domain.mentoring.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;

public interface MentoringApplicationRepository extends JpaRepository<MentoringApplication, UUID> {

    // 멘티 ID로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByMenteeId(UUID menteeId, Pageable pageable);

    // 멘토 ID(program.mentor.id)로 신청 내역 조회 (페이징)
    Page<MentoringApplication> findByProgramMentorId(UUID mentorId, Pageable pageable);

    boolean existsByMenteeIdAndProgramIdAndStatusIn(UUID menteeId, UUID programId, List<ApplicationStatus> statuses);

    List<MentoringApplication> findByStatusAndUpdatedAtBefore(ApplicationStatus status, OffsetDateTime dateTime);

    // 동시 환불/정산 경합 및 마일리지 중복 지급 방지를 위한 비관적 쓰기 락 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MentoringApplication a where a.id = :id")
    Optional<MentoringApplication> findByIdForUpdate(@Param("id") UUID id);
}