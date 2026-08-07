package com.gamerin.backend.domain.report.dto.response;

import com.gamerin.backend.domain.report.entity.ReportReasonCode;

/**
 * 신고 사유 목록 조회 시 제공되는 사유 코드, 라벨, 상세
 * 설명 응답 DTO
 */
public record ReportReasonResponse(
        String code,
        String label) {
    public static ReportReasonResponse from(ReportReasonCode
  reasonCode) {
            return new ReportReasonResponse(
                    reasonCode.name(),
                    reasonCode.getDescription() //  ReportReasonCode의 한글명("욕설 및 비방" 등)을 label로 사용
            );
        }
}