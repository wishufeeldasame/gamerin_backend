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

    // 소프트 삭제된 프로그램은 목록에 노출하지 않는다
    @Query("SELECT p FROM MentoringProgram p " +
            "WHERE (:gameName IS NULL OR p.gameName = :gameName) " +
            "AND (:mentorId IS NULL OR p.mentor.userId = :mentorId) " +
            "AND p.deletedAt IS NULL")
    Page<MentoringProgram> findByFilters(
            @Param("gameName") String gameName,
            @Param("mentorId") UUID mentorId,
            Pageable pageable);

    // 소프트 삭제된 프로그램은 락 대상에서 제외 (삭제 후 신청 불가)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select program from MentoringProgram program where program.id = :id and program.deletedAt IS NULL")
        Optional<MentoringProgram> findByIdForUpdate(@Param("id") UUID id);

}
