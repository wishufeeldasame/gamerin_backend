package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.AdminAuditLogResponse;
import com.gamerin.backend.domain.admin.dto.response.AdminDashboardStatsResponse;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.repository.ReportCountRepository;
import com.gamerin.backend.domain.report.repository.ReportRepository;
import com.gamerin.backend.domain.report.repository.UserPenaltyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 어드민 대시보드 핵심 통계 수치 집계 및 관리자 감사 로그(Audit Log) 검색 조회 서비스
 */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private final ReportRepository reportRepository;
    private final UserPenaltyRepository userPenaltyRepository;
    private final ReportCountRepository reportCountRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;

    public AdminDashboardService(
            ReportRepository reportRepository,
            UserPenaltyRepository userPenaltyRepository,
            ReportCountRepository reportCountRepository,
            AdminAuditLogRepository adminAuditLogRepository
    ) {
        this.reportRepository = reportRepository;
        this.userPenaltyRepository = userPenaltyRepository;
        this.reportCountRepository = reportCountRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
    }

    /**
     * 어드민 대시보드 상단 통계 카드 지표 조회
     */
    public AdminDashboardStatsResponse getDashboardStats() {
        long receivedReports = reportRepository.countByStatus(ReportStatus.RECEIVED);
        long inReviewReports = reportRepository.countByStatus(ReportStatus.IN_REVIEW);
        long resolvedReports = reportRepository.countByStatus(ReportStatus.RESOLVED);
        long rejectedReports = reportRepository.countByStatus(ReportStatus.REJECTED);
        long activePenalties = userPenaltyRepository.countDistinctUserIdByIsActiveTrue();
        long hiddenContents = reportCountRepository.countByIsHiddenTrue();

        return new AdminDashboardStatsResponse(
                receivedReports,
                inReviewReports,
                resolvedReports,
                rejectedReports,
                activePenalties,
                hiddenContents
        );
    }

    /**
     * 관리자 작업 이력 감사 로그(Audit Log) 검색 조회
     */
    public Page<AdminAuditLogResponse> getAuditLogs(
            UUID adminId,
            String actionType,
            ReportTargetType targetType,
            Pageable pageable
    ) {
        return adminAuditLogRepository.searchLogs(adminId, actionType, targetType, pageable)
                .map(AdminAuditLogResponse::from);
    }
}