package com.gamerin.backend.domain.report.dto.request;

import com.gamerin.backend.domain.report.entity.ReportReasonCode;
import com.gamerin.backend.domain.report.entity.ReportStatus;
import com.gamerin.backend.domain.report.entity.ReportTargetType;

 /** 어드민이 신고 목록을 조회할 때 상태, 대상, 사유, 키워드 검색 조건을 담는 DTO */
public record ReportSearchCondition(
        ReportStatus status,
        ReportTargetType targetType,
        ReportReasonCode reasonCode,
        String keyword) {
}