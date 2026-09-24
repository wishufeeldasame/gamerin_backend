package com.gamerin.backend.domain.report.entity;

public enum ReportReasonCode {
    PROFANITY("욕설 및 비방"),
    SPAM("스팸 및 반복 홍보"),
    INAPPROPRIATE("부적절한 콘텐츠"),
    IMPERSONATION("사칭 및 허위 정보"),
    OTHER("기타");

    private final String description;

    ReportReasonCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}