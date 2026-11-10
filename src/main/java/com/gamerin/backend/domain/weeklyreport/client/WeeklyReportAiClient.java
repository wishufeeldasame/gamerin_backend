package com.gamerin.backend.domain.weeklyreport.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI Chat Completions 호환 API로 주간 분석 문장을 만든다.
 * provider(factchat: 학교 Mindlogic 게이트웨이, nvidia: NVIDIA Build)에 따라 URL·키·모델만 달라진다.
 */
@Component
public class WeeklyReportAiClient {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportAiClient.class);
    private static final int ATTEMPTS = 2;
    private static final int MAX_HEADLINE = 60;
    private static final int MAX_SUMMARY = 400;
    private static final int MAX_FOCUS = 250;
    private static final String SYSTEM_PROMPT = """
            당신은 발로란트 경쟁전 주간 전적을 해설하는 코치입니다. 입력은 대상 주(current)와 전주(previous)의 집계 JSON입니다.
            규칙:
            - 입력에 있는 숫자만 인용합니다. 입력에 없는 수치, 경기 내용, 원인을 지어내지 않습니다.
            - 경기 상세를 받지 못한 기록이 있을 수 있으므로 숫자는 확인된 경기 기준으로 설명하고 전체 경기 수라고 단정하지 않습니다.
            - previous가 null이면 전주와 비교하지 않습니다. 값이 null인 항목은 언급하지 않습니다.
            - matches가 5 미만이면 추세를 단정하지 말고 표본이 적다고 밝힙니다.
            - 한국어 존댓말로 씁니다. 닉네임이나 계정 정보는 쓰지 않습니다.
            - 다른 설명 없이 JSON 객체 하나만 출력합니다: {"headline": "...", "summary": "...", "focus": "..."}
            - headline은 40자 이내 한 문장, summary는 2~4문장 300자 이내, focus는 다음 주에 점검할 행동 1~2문장 150자 이내입니다.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String model;
    private final boolean configured;

    public WeeklyReportAiClient(
            @Value("${app.weekly-report.ai.provider:factchat}") String provider,
            @Value("${app.weekly-report.ai.factchat.url:}") String factchatUrl,
            @Value("${app.weekly-report.ai.factchat.key:}") String factchatKey,
            @Value("${app.weekly-report.ai.factchat.model:}") String factchatModel,
            @Value("${app.weekly-report.ai.nvidia.url:}") String nvidiaUrl,
            @Value("${app.weekly-report.ai.nvidia.key:}") String nvidiaKey,
            @Value("${app.weekly-report.ai.nvidia.model:}") String nvidiaModel,
            @Value("${app.weekly-report.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.weekly-report.ai.read-timeout:60s}") Duration readTimeout,
            ObjectMapper objectMapper
    ) {
        boolean nvidia = "nvidia".equalsIgnoreCase(provider == null ? "" : provider.strip());
        this.url = trimToNull(nvidia ? nvidiaUrl : factchatUrl);
        this.model = trimToNull(nvidia ? nvidiaModel : factchatModel);
        String key = trimToNull(nvidia ? nvidiaKey : factchatKey);
        this.configured = url != null && model != null && key != null;
        this.objectMapper = objectMapper;
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)));
        if (key != null) {
            builder.defaultHeader("Authorization", "Bearer " + key);
        }
        this.restClient = builder.build();
    }

    /** 분석에 쓴 모델 이름. 설정되지 않았으면 null이다. */
    public String model() {
        return configured ? model : null;
    }

    /** 키·모델이 없거나 2회 모두 실패하면 비어 있다. 입력은 집계 숫자뿐이다. */
    public Optional<WeeklyAnalysis> analyze(WeeklyStats stats) {
        if (!configured) {
            return Optional.empty();
        }
        String input;
        try {
            // 경기 ID·날짜·오류 진단은 외부 AI에 보내지 않는다.
            input = objectMapper.writeValueAsString(new WeeklyStats(stats.current(), stats.previous(), stats.tierChange()));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.3,
                // 추론형 모델은 추론 토큰도 이 한도에서 쓴다.
                "max_tokens", 2000,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", input)));
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
                String content = response == null ? null
                        : response.path("choices").path(0).path("message").path("content").asText(null);
                Optional<WeeklyAnalysis> analysis = parse(content, objectMapper);
                if (analysis.isPresent()) {
                    return analysis;
                }
                log.warn("Weekly report AI response rejected. attempt={}", attempt);
            } catch (RestClientException e) {
                log.warn("Weekly report AI request failed. attempt={} error={}", attempt, e.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    /** 코드 펜스나 앞뒤 문장을 걷어 내고 세 필드의 존재와 길이를 검증한다. */
    static Optional<WeeklyAnalysis> parse(String content, ObjectMapper objectMapper) {
        if (content == null) {
            return Optional.empty();
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(content.substring(start, end + 1));
            String headline = text(json, "headline", MAX_HEADLINE);
            String summary = text(json, "summary", MAX_SUMMARY);
            String focus = text(json, "focus", MAX_FOCUS);
            if (headline == null || summary == null || focus == null) {
                return Optional.empty();
            }
            return Optional.of(new WeeklyAnalysis(headline, summary, focus));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode json, String field, int maxLength) {
        JsonNode node = json.path(field);
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
