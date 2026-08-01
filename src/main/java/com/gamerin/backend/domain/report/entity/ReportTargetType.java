package com.gamerin.backend.domain.report.entity;

public enum ReportTargetType {
    POST("게시글"),
    COMMENT("댓글"),
    USER("사용자"),
    MENTORING("멘토링"),
    MESSAGE("메시지");

    private final String description;

    ReportTargetType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}