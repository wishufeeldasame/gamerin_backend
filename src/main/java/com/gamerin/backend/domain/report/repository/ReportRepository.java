package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

//신고 접수 조회, 동일 유저 중복 신고 검증, 어드민 검색 및 필터링(상태, 대상, 사유, 키워드) 쿼리 제공.
public interface ReportRepository extends
        JpaRepository<Report, UUID> {

    // 동일 유저의 동일 대상 중복 신고 방지 검증
    boolean existsByReporterIdAndTargetTypeAndTargetId(UUID reporterId, ReportTargetType targetType, UUID targetId);

    // 신고 코드(RPT-1001 등) 단건 조회
    Optional<Report> findByReportCode(String reportCode);

    // 대시보드 상태별 건수 집계
    long countByStatus(ReportStatus status);

    // 어드민 전용 신고 동적 검색 및 페이징 (admin_reports.png 대응)
    @Query("SELECT r FROM Report r " +
               "LEFT JOIN r.reporter u " +
               "WHERE (:status IS NULL OR r.status = :status) " +
               "AND (:targetType IS NULL OR r.targetType = :targetType) " +
               "AND (:reasonCode IS NULL OR r.reasonCode = :reasonCode) " +
               "AND (:keyword IS NULL OR :keyword = '' OR " +
               "     LOWER(r.reportCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
               "     LOWER(r.details) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
               "     LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')))")
        Page<Report> searchReports(
                @Param("status") ReportStatus status,
                @Param("targetType") ReportTargetType targetType,
                @Param("reasonCode") ReportReasonCode reasonCode,
                @Param("keyword") String keyword,
                Pageable pageable
        );
}