package com.gamerin.backend.global.logging;

import jakarta.servlet.ServletRequest;

public final class JsonLogContext {

    private static final String FAILURE_REASON_ATTRIBUTE = JsonLogContext.class.getName() + ".failureReason";

    private JsonLogContext() {
    }

    public static void setFailureReason(ServletRequest request, String reason) {
        if (request == null || reason == null || reason.isBlank()) {
            return;
        }
        request.setAttribute(FAILURE_REASON_ATTRIBUTE, reason);
    }

    public static String getFailureReason(ServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(FAILURE_REASON_ATTRIBUTE);
        return value instanceof String reason && !reason.isBlank() ? reason : null;
    }
}
