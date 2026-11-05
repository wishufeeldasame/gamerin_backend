package com.gamerin.backend.domain.admin.dto.response;

/**
 * 어드민 멘토링 운영 요약 통계 응답 DTO
 */
public record AdminMentoringSummaryResponse(
        long pendingMentorCount, // 승인 대기 신청 수
        long activeProgramCount, // 운영 중 프로그램 수
        long monthlySessionCount, // 이번 달 세션(신청) 수
        long escrowHeldAmount // 에스크로 보관 중인 정산 예정 총액
) {
}