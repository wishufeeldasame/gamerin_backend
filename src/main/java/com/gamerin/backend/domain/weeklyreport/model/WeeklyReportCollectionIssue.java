package com.gamerin.backend.domain.weeklyreport.model;

import java.time.Instant;

/** 관리자에게만 공개하는 수집 진단. 이력 조회 자체의 실패면 경기 정보는 null이다. */
public record WeeklyReportCollectionIssue(
        String matchId, Instant startedAt, String map, Integer httpStatus, String reason) {
}
