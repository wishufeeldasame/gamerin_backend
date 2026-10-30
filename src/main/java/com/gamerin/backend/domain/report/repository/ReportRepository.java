package com.gamerin.backend.domain.report.repository;

import com.gamerin.backend.domain.report.entity.Report;
import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

//신고 접수 조회, 동일 유저 중복 신고 검증, 어드민 검색 및 필터링(상태, 대상, 사유, 키워드) 쿼리 제공.
public interface ReportRepository extends
                JpaRepository<Report, UUID> {

        // 동일 유저의 동일 대상 중복 신고 방지 검증
        boolean existsByReporterIdAndTargetTypeAndTargetId(UUID reporterId, ReportTargetType targetType, UUID targetId);

        // 신고 코드(RPT-1001 등) 단건 조회
        Optional<Report> findByReportCode(String reportCode);

        // 특정 유저(신고자 본인)의 신고 접수 내역 최신순 페이징 조회
        Page<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId, Pageable pageable);

        // 대시보드 상태별 건수 집계
        long countByStatus(ReportStatus status);

        // 특정 대상(유저, 게시글 등)이 받은 누적 신고 건수 집계
        long countByTargetTypeAndTargetId(ReportTargetType targetType, UUID targetId);

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
                        Pageable pageable);

        // MENTORING 신고는 신청 ID로 접수되므로 신청의 프로그램 기준으로 일괄 집계 (N+1 방지용)
        @Query("SELECT a.program.id AS programId, COUNT(r) AS cnt " +
                        "FROM Report r JOIN MentoringApplication a ON a.id = r.targetId " +
                        "WHERE r.targetType = com.gamerin.backend.domain.report.entity.ReportTargetType.MENTORING " +
                        "AND a.program.id IN :programIds " +
                        "GROUP BY a.program.id")
        List<Object[]> countMentoringReportsByProgramIdIn(@Param("programIds") List<UUID> programIds);

        // 신고 원클릭 처리 동시성 제어용 비관적 쓰기 락 조회
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT r FROM Report r WHERE r.id = :id")
        Optional<Report> findByIdForUpdate(@Param("id") UUID id);

        // 신고 코드 기반 비관적 쓰기 락 조회
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT r FROM Report r WHERE r.reportCode = :reportCode")
        Optional<Report> findByReportCodeForUpdate(@Param("reportCode") String reportCode);

        // 특정 대상(멘토링 신청 건 등)에 대해 특정 상태 목록(예: RECEIVED, IN_REVIEW 미결 상태)의 신고가 존재하는지 확인
        // -> 7일 자동 정산 스케줄러에서 분쟁(이의제기) 진행 중인 신청 건의 정산을 보류할 때 사용
        boolean existsByTargetTypeAndTargetIdAndStatusIn(
                        ReportTargetType targetType,
                        UUID targetId,
                        List<ReportStatus> statuses);

        // 프로그램 ID 자체(targetId = programId)로 직접 접수된 MENTORING 신고 건수 일괄 집계 (N+1 방지용)
        @Query("SELECT r.targetId AS programId, COUNT(r) AS cnt " +
                        "FROM Report r " +
                        "WHERE r.targetType = com.gamerin.backend.domain.report.entity.ReportTargetType.MENTORING " +
                        "AND r.targetId IN :programIds " +
                        "GROUP BY r.targetId")
        List<Object[]> countDirectMentoringReportsByProgramIdIn(@Param("programIds") List<UUID> programIds);
}