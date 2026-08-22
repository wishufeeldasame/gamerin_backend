// 타겟별 신고 누적 횟수 관리, 비관적 락(PESSIMISTIC_WRITE)을 통한 동시성 제어 및 자동 숨김 목록 조회 리포지토리
package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface ReportCountRepository extends JpaRepository<ReportCount, UUID> {

    // 동시 신고 시 카운트 유실 및 Race Condition 방지를 위한 비관적 락 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReportCount> findByTargetTypeAndTargetId(ReportTargetType targetType, UUID targetId);

    // 자동 숨김 처리된 콘텐츠 목록 페이징 조회
    Page<ReportCount> findByIsHiddenTrue(Pageable pageable);

    // 총 숨김 처리 건수 (어드민 대시보드용)
    long countByIsHiddenTrue();
}