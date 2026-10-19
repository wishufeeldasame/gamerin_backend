package com.gamerin.backend.domain.post.moderation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gamerin.backend.global.logging.JsonConsoleLogger;

@Component
public class OpenAiModerationClient {

    private static final String BASE_URL = "https://api.openai.com";
    private static final String DEFAULT_MODEL = "omni-moderation-latest";

    private final RestClient restClient;
    private final boolean apiKeyConfigured;
    private final boolean enabled;
    private final String model;
    private final int maxParallelRequests;

    @Autowired
    public OpenAiModerationClient(
            @Value("${openai.api.key:}") String apiKey,
            @Value("${openai.moderation.enabled:true}") boolean enabled,
            @Value("${openai.moderation.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${openai.moderation.max-parallel-requests:4}") int maxParallelRequests,
            @Value("${openai.moderation.connect-timeout:3s}") Duration connectTimeout,
            @Value("${openai.moderation.read-timeout:10s}") Duration readTimeout
    ) {
        this(apiKey, enabled, model, maxParallelRequests,
                RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout)));
    }

    static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout));
    }

    OpenAiModerationClient(
            String apiKey,
            boolean enabled,
            String model,
            int maxParallelRequests,
            RestClient.Builder restClientBuilder
    ) {
        if (maxParallelRequests < 1) {
            throw new IllegalArgumentException("OpenAI moderation max parallel requests must be at least 1.");
        }
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.maxParallelRequests = maxParallelRequests;
        this.restClient = restClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public ModerationDecision moderate(List<ModerationInput> inputs) {
        if (!enabled || inputs == null || inputs.isEmpty()) {
            JsonConsoleLogger.info("moderation.openai.skipped", "success", resolveSkipReason(inputs), Map.of(
                    "enabled", enabled,
                    "inputCount", inputs == null ? 0 : inputs.size()
            ));
            return ModerationDecision.allowed();
        }

        validateApiKeyConfigured();

        List<List<ModerationInput>> requests = splitRequestsByImageLimit(inputs);
        if (requests.isEmpty()) {
            return ModerationDecision.allowed();
        }
        long startedAt = System.nanoTime();
        String outcome = "failed";
        try {
            ModerationDecision decision = requests.size() == 1
                    ? moderateSingleRequest(requests.get(0))
                    : moderateInParallel(requests);
            outcome = decision.flagged() ? "flagged" : "allowed";
            return decision;
        } finally {
            JsonConsoleLogger.info("moderation.openai.completed", "failed".equals(outcome) ? "failure" : "success", outcome, Map.of(
                    "model", model,
                    "requestCount", requests.size(),
                    "maxParallelRequests", maxParallelRequests,
                    "durationMs", (System.nanoTime() - startedAt) / 1_000_000
            ));
        }
    }

    private ModerationDecision moderateInParallel(List<List<ModerationInput>> requests) {
        // 차단 I/O 대기라 가상 스레드를 쓰고, 고정 크기 풀로 호출당 동시 요청 수를 제한한다.
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(requests.size(), maxParallelRequests),
                Thread.ofVirtual().name("openai-moderation-", 0).factory()
        );
        try {
            CompletionService<ModerationDecision> completionService = new ExecutorCompletionService<>(executor);
            requests.forEach(requestInputs -> completionService.submit(() -> moderateSingleRequest(requestInputs)));

            for (int completed = 0; completed < requests.size(); completed++) {
                ModerationDecision decision = completionService.take().get();
                if (decision.flagged()) {
                    return decision;
                }
            }
            return ModerationDecision.allowed();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to moderate content with OpenAI.", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to moderate content with OpenAI.", ex);
        } finally {
            // 판정이 끝나면 남은 요청은 인터럽트로 취소하고 완료를 기다리지 않는다.
            executor.shutdownNow();
        }
    }

    static List<List<ModerationInput>> splitRequestsByImageLimit(List<ModerationInput> inputs) {
        List<ModerationInput> normalizedInputs = inputs.stream()
                .filter(input -> input != null)
                .toList();
        List<ModerationInput> imageInputs = normalizedInputs.stream()
                .filter(ModerationInput::isImage)
                .toList();

        if (imageInputs.size() <= 1) {
            return normalizedInputs.isEmpty() ? List.of() : List.of(normalizedInputs);
        }

        List<ModerationInput> nonImageInputs = normalizedInputs.stream()
                .filter(input -> !input.isImage())
                .toList();

        List<List<ModerationInput>> requests = new ArrayList<>();
        for (ModerationInput imageInput : imageInputs) {
            List<ModerationInput> requestInputs = new ArrayList<>(nonImageInputs);
            requestInputs.add(imageInput);
            requests.add(List.copyOf(requestInputs));
        }
        return List.copyOf(requests);
    }

    private ModerationDecision moderateSingleRequest(List<ModerationInput> inputs) {
        List<Map<String, Object>> requestInputs = inputs.stream()
                .map(ModerationInput::toRequestBody)
                .toList();

        try {
            OpenAiModerationResponse response = restClient.post()
                    .uri("/v1/moderations")
                    .body(Map.of(
                            "model", model,
                            "input", requestInputs
                    ))
                    .retrieve()
                    .body(OpenAiModerationResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                JsonConsoleLogger.failure("moderation.openai.request", "empty_response", Map.of(
                        "model", model,
                        "inputCount", inputs.size()
                ));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Moderation response was empty.");
            }

            ModerationDecision decision = ModerationDecision.from(response.results());
            JsonConsoleLogger.success("moderation.openai.request", Map.of(
                    "model", model,
                    "inputCount", inputs.size(),
                    "flagged", decision.flagged(),
                    "flaggedCategories", decision.flaggedCategories()
            ));
            return decision;
        } catch (HttpClientErrorException.TooManyRequests ex) {
            JsonConsoleLogger.warnFailure("moderation.openai.request", "rate_limit_exceeded", Map.of(
                    "model", model,
                    "inputCount", inputs.size(),
                    "statusCode", ex.getStatusCode().value()
            ));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "OpenAI moderation rate limit exceeded.", ex);
        } catch (HttpClientErrorException.BadRequest ex) {
            JsonConsoleLogger.warnFailure("moderation.openai.request", "bad_request", Map.of(
                    "model", model,
                    "inputCount", inputs.size(),
                    "statusCode", ex.getStatusCode().value(),
                    "responseBody", truncate(ex.getResponseBodyAsString())
            ));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI moderation request was rejected.", ex);
        } catch (RestClientException ex) {
            if (Thread.currentThread().isInterrupted()) {
                // 다른 요청의 판정으로 취소된 경우라 실패 로그를 남기지 않는다.
                JsonConsoleLogger.info("moderation.openai.request", "cancelled", "cancelled", Map.of(
                        "model", model,
                        "inputCount", inputs.size()
                ));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to moderate content with OpenAI.", ex);
            }
            JsonConsoleLogger.failure("moderation.openai.request", "rest_client_exception", Map.of(
                    "model", model,
                    "inputCount", inputs.size(),
                    "exception", ex.getClass().getSimpleName()
            ));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to moderate content with OpenAI.", ex);
        }
    }

    private void validateApiKeyConfigured() {
        if (!apiKeyConfigured) {
            JsonConsoleLogger.failure("moderation.openai.config", "api_key_not_configured", Map.of(
                    "enabled", enabled,
                    "model", model
            ));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenAI API key is not configured.");
        }
    }

    private String resolveSkipReason(List<ModerationInput> inputs) {
        if (!enabled) {
            return "moderation_disabled";
        }
        return inputs == null || inputs.isEmpty() ? "empty_input" : "unknown";
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }

    public record ModerationInput(String type, String text, String imageDataUrl) {

        public static ModerationInput text(String text) {
            return new ModerationInput("text", text, null);
        }

        public static ModerationInput image(String imageDataUrl) {
            return new ModerationInput("image_url", null, imageDataUrl);
        }

        private boolean isImage() {
            return "image_url".equals(type);
        }

        private Map<String, Object> toRequestBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            if ("text".equals(type)) {
                body.put("text", text);
            } else {
                body.put("image_url", Map.of("url", imageDataUrl));
            }
            return body;
        }
    }

    public record ModerationDecision(boolean flagged, List<String> flaggedCategories) {

        private static ModerationDecision allowed() {
            return new ModerationDecision(false, List.of());
        }

        private static ModerationDecision from(List<OpenAiModerationResult> results) {
            List<String> categories = new ArrayList<>();
            boolean flagged = false;

            for (OpenAiModerationResult result : results) {
                if (result == null || !result.flagged()) {
                    continue;
                }

                flagged = true;
                if (result.categories() == null) {
                    continue;
                }

                result.categories()
                        .entrySet()
                        .stream()
                        .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                        .map(Map.Entry::getKey)
                        .filter(category -> !categories.contains(category))
                        .forEach(categories::add);
            }

            return new ModerationDecision(flagged, List.copyOf(categories));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiModerationResponse(
            String id,
            String model,
            List<OpenAiModerationResult> results
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiModerationResult(
            boolean flagged,
            Map<String, Boolean> categories,
            @JsonProperty("category_scores") Map<String, Double> categoryScores,
            @JsonProperty("category_applied_input_types") Map<String, List<String>> categoryAppliedInputTypes
    ) {
    }
}
