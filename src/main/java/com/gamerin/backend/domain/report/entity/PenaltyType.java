package com.gamerin.backend.domain.report.entity;

public enum PenaltyType {
    WARNING("경고"),
    SUSPENSION_7D("7일 정지"),
    SUSPENSION_30D("30일 정지"),
    PERMANENT_BAN("영구 정지");

    private final String description;

    PenaltyType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}