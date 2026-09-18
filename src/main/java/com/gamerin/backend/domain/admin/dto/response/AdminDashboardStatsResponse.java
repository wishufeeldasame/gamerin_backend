package com.gamerin.backend.domain.admin.dto.response;

/**
 * 어드민 대시보드 상단 카드의 신고 현황, 제재 유저 수,
 * 숨김 콘텐츠 수 통계를 반환하는 응답 DTO
 */
public record AdminDashboardStatsResponse(
    long receivedReportsCount, // 접수 대기 건수 (RECEIVED)
    long inReviewReportsCount, // 검토 중 건수 (IN_REVIEW)
    long resolvedReportsCount, // 처리 완료 건수 (RESOLVED)
    long rejectedReportsCount, // 반려 건수 (REJECTED)
    long activePenaltiesCount, // 현재 활성 제재 유저 수
    long hiddenContentsCount // 누적 자동 숨김 콘텐츠 수) 
){}