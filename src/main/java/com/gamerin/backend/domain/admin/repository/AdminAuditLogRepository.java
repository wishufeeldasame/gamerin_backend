package com.gamerin.backend.domain.admin.repository;

import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

//관리자 액션(숨김, 제재, 환불 등) 감사 로그 저장 및 필터링 검색.
public interface AdminAuditLogRepository extends
        JpaRepository<AdminAuditLog, UUID> {

    // 감사 로그 검색 (admin_audit-logs.png 대응)
    @Query("SELECT a FROM AdminAuditLog a " + "WHERE (:adminId IS NULL OR a.admin.id = :adminId) "
            + "AND (:actionType IS NULL OR :actionType = '' OR a.actionType = :actionType) "
            + "AND (:targetType IS NULL OR a.targetType = :targetType)")

    Page<AdminAuditLog> searchLogs(
            @Param("adminId") UUID adminId,
            @Param("actionType") String actionType,
            @Param("targetType") ReportTargetType targetType,
            Pageable pageable);
}