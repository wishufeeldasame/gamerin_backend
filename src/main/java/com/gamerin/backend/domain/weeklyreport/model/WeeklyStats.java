package com.gamerin.backend.domain.weeklyreport.model;

import java.util.List;

/**
 * 대상 주와 그 전주의 집계. 경기가 없는 주는 null이다.
 * tierChange는 두 주 모두 티어가 있을 때 UP·DOWN·SAME, 아니면 null이다.
 */
public record WeeklyStats(WeekStats current, WeekStats previous, String tierChange,
        List<WeeklyReportCollectionIssue> collectionIssues) {

    public WeeklyStats {
        collectionIssues = collectionIssues == null ? List.of() : List.copyOf(collectionIssues);
    }

    public WeeklyStats(WeekStats current, WeekStats previous, String tierChange) {
        this(current, previous, tierChange, List.of());
    }
}
