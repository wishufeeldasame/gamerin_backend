package com.gamerin.backend.domain.mentoring.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;

public interface MentoringProgramRepository extends JpaRepository<MentoringProgram, UUID> {

    // 게임 이름별 필터링 조회
    Page<MentoringProgram> findByGameName(String gameName, Pageable pageable);

    @Query("SELECT p FROM MentoringProgram p " +
       "WHERE (:gameName IS NULL OR p.gameName = :gameName) " +
       "AND (:mentorId IS NULL OR p.mentor.userId = :mentorId)")
    Page<MentoringProgram> findByFilters(
        @Param("gameName") String gameName, 
        @Param("mentorId") UUID mentorId, 
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select program from MentoringProgram program where program.id = :id")
    Optional<MentoringProgram> findByIdForUpdate(@Param("id") UUID id);

}
