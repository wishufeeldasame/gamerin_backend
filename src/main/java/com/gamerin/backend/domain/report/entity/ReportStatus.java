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

    /**
     * 처리 완료(RESOLVED) 또는 반려(REJECTED)로 종결된 상태인지 여부.
     * 종결된 신고는 다른 상태로 되돌릴 수 없다.
     */
    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }
}