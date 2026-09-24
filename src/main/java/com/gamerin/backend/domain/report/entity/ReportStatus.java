package com.gamerin.backend.domain.report.entity;

public enum ReportStatus {
    RECEIVED("접수"),
    IN_REVIEW("검토 중"),
    RESOLVED("처리 완료"),
    REJECTED("반려");

    private final String description;

    ReportStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}