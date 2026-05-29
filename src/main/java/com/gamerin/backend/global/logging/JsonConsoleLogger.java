package com.gamerin.backend.global.logging;

import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonConsoleLogger {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonConsoleLogger() {
    }

    public static void success(String event, Map<String, ?> details) {
        write("INFO", event, "success", null, details);
    }

    public static void failure(String event, String reason, Map<String, ?> details) {
        write("ERROR", event, "failure", reason, details);
    }

    public static void warnFailure(String event, String reason, Map<String, ?> details) {
        write("WARN", event, "failure", reason, details);
    }

    public static void info(String event, String status, String reason, Map<String, ?> details) {
        write("INFO", event, status, reason, details);
    }

    private static void write(String level, String event, String status, String reason, Map<String, ?> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("level", level);
        payload.put("event", event);
        payload.put("status", status);
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        if (details != null && !details.isEmpty()) {
            payload.put("details", new LinkedHashMap<>(details));
        }

        PrintStream stream = "ERROR".equals(level) || "WARN".equals(level) ? System.err : System.out;
        stream.println(toJson(payload));
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"timestamp\":\"%s\",\"level\":\"ERROR\",\"event\":\"logging.json_serialize\",\"status\":\"failure\",\"reason\":\"%s\"}"
                    .formatted(Instant.now(), escape(ex.getMessage()));
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
