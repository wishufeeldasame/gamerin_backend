package com.gamerin.backend.domain.mentoring.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;

public interface MentoringProgramRepository extends JpaRepository<MentoringProgram, UUID> {

    // 게임 이름별 필터링 조회
    Page<MentoringProgram> findByGameName(String gameName, Pageable pageable);

}
