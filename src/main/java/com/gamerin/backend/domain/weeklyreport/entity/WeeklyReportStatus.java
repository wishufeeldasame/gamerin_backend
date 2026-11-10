package com.gamerin.backend.domain.weeklyreport.entity;

/** READY: 분석까지 생성됨. NO_DATA: 대상 주에 경기가 없음. FAILED: 수집 실패. */
public enum WeeklyReportStatus {
    READY,
    NO_DATA,
    FAILED
}
