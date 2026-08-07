package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.ReportCount;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

//타겟별 신고 누적 횟수 관리, 5회 이상 숨김 처리된 콘텐츠 목록 및 통계 조회.
public interface ReportCountRepository extends
        JpaRepository<ReportCount, UUID> {

    // 대상별 신고 카운트 단건 조회
    Optional<ReportCount>findByTargetTypeAndTargetId(ReportTargetType targetType, UUID targetId);

    // 자동 숨김 처리된 콘텐츠 목록 페이징 조회(admin_content.png 대응)

    Page<ReportCount> findByIsHiddenTrue(Pageable pageable);

    // 총 숨김 처리 건수 (어드민 대시보드용)
    long countByIsHiddenTrue();
}