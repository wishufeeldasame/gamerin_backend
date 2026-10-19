package com.gamerin.backend.domain.post.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationDecision;
import com.gamerin.backend.domain.post.moderation.OpenAiModerationClient.ModerationInput;

class OpenAiModerationClientTest {

    private static final String MODERATIONS_URL = "https://api.openai.com/v1/moderations";
    private static final String ALLOWED_RESPONSE = """
            {"id":"modr-1","model":"omni-moderation-latest","results":[{"flagged":false,"categories":{"violence":false}}]}
            """;
    private static final String FLAGGED_RESPONSE = """
            {"id":"modr-2","model":"omni-moderation-latest","results":[{"flagged":true,"categories":{"violence":true,"sexual":false}}]}
            """;
    private static final String EMPTY_RESPONSE = """
            {"id":"modr-3","model":"omni-moderation-latest","results":[]}
            """;

    @Test
    void moderateSendsSingleRequestOnCallerPathWhenOnlyOneImage() {
        Fixture fixture = fixture(true, 4);
        fixture.server().expect(times(1), requestTo(MODERATIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(body(request)).contains("image post", "data:image/jpeg;base64,only"))
                .andRespond(withSuccess(FLAGGED_RESPONSE, MediaType.APPLICATION_JSON));

        ModerationDecision decision = fixture.client().moderate(inputs("image post", "only"));

        assertThat(decision.flagged()).isTrue();
        assertThat(decision.flaggedCategories()).containsExactly("violence");
        fixture.server().verify();
    }

    @Test
    void moderateSendsSplitRequestsConcurrently() {
        Fixture fixture = fixture(true, 4);
        CountDownLatch allStarted = new CountDownLatch(4);
        fixture.server().expect(times(4), requestTo(MODERATIONS_URL))
                .andRespond(request -> {
                    allStarted.countDown();
                    // 직렬 실행이면 4개가 동시에 도달하지 못해 대기가 타임아웃된다.
                    if (!await(allStarted, 5)) {
                        throw new IOException("requests were not sent concurrently");
                    }
                    return withSuccess(ALLOWED_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                });

        ModerationDecision decision = fixture.client().moderate(inputs("four images", "a", "b", "c", "d"));

        assertThat(decision.flagged()).isFalse();
        assertThat(decision.flaggedCategories()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void moderateLimitsConcurrentRequestsToConfiguredMax() {
        Fixture fixture = fixture(true, 2);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        fixture.server().expect(times(4), requestTo(MODERATIONS_URL))
                .andRespond(request -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return withSuccess(ALLOWED_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                });

        ModerationDecision decision = fixture.client().moderate(inputs("four images", "a", "b", "c", "d"));

        assertThat(decision.flagged()).isFalse();
        assertThat(maxInFlight.get()).isBetween(1, 2);
        fixture.server().verify();
    }

    @Test
    void moderateReturnsFlaggedDecisionWithoutWaitingForRemainingRequests() {
        Fixture fixture = fixture(true, 4);
        CountDownLatch release = new CountDownLatch(1);
        fixture.server().expect(manyTimes(), requestTo(MODERATIONS_URL))
                .andRespond(request -> {
                    if (body(request).contains("bad-frame")) {
                        return withSuccess(FLAGGED_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                    }
                    if (!await(release, 10)) {
                        throw new IOException("cancelled");
                    }
                    return withSuccess(ALLOWED_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request);
                });

        long startedAt = System.nanoTime();
        ModerationDecision decision = fixture.client().moderate(inputs("gif post", "a", "bad-frame", "c"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        release.countDown();

        assertThat(decision.flagged()).isTrue();
        assertThat(decision.flaggedCategories()).containsExactly("violence");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void moderateKeepsTooManyRequestsStatusWhenOneSplitRequestIsRateLimited() {
        assertSplitFailureStatus(withTooManyRequests(), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void moderateMapsBadRequestFromOneSplitRequestToBadGateway() {
        assertSplitFailureStatus(withBadRequest().body("{\"error\":\"invalid image\"}"), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void moderateMapsTransportFailureFromOneSplitRequestToBadGateway() {
        assertSplitFailureStatus(withException(new IOException("connection reset")), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void moderateMapsEmptyResponseFromOneSplitRequestToBadGateway() {
        assertSplitFailureStatus(withSuccess(EMPTY_RESPONSE, MediaType.APPLICATION_JSON), HttpStatus.BAD_GATEWAY);
    }

    @Test
    void moderateKeepsStatusForSingleRequestFailure() {
        Fixture fixture = fixture(true, 4);
        fixture.server().expect(times(1), requestTo(MODERATIONS_URL)).andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client().moderate(inputs("image post", "only")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void moderateSkipsRequestsWhenDisabledOrInputIsEmpty() {
        Fixture disabled = fixture(false, 4);
        assertThat(disabled.client().moderate(inputs("image post", "a", "b")).flagged()).isFalse();
        disabled.server().verify();

        Fixture enabled = fixture(true, 4);
        assertThat(enabled.client().moderate(List.of()).flagged()).isFalse();
        assertThat(enabled.client().moderate(null).flagged()).isFalse();
        assertThat(enabled.client().moderate(Arrays.asList((ModerationInput) null)).flagged()).isFalse();
        enabled.server().verify();
    }

    private static void assertSplitFailureStatus(ResponseCreator failure, HttpStatus expectedStatus) {
        Fixture fixture = fixture(true, 4);
        fixture.server().expect(manyTimes(), requestTo(MODERATIONS_URL))
                .andRespond(request -> body(request).contains("broken")
                        ? failure.createResponse(request)
                        : withSuccess(ALLOWED_RESPONSE, MediaType.APPLICATION_JSON).createResponse(request));

        assertThatThrownBy(() -> fixture.client().moderate(inputs("image post", "a", "broken", "c")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expectedStatus));
    }

    private static Fixture fixture(boolean enabled, int maxParallelRequests) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        OpenAiModerationClient client = new OpenAiModerationClient("test-key", enabled, null, maxParallelRequests, builder);
        return new Fixture(client, server);
    }

    private static List<ModerationInput> inputs(String text, String... imageNames) {
        List<ModerationInput> inputs = new ArrayList<>();
        inputs.add(ModerationInput.text(text));
        for (String imageName : imageNames) {
            inputs.add(ModerationInput.image("data:image/jpeg;base64," + imageName));
        }
        return inputs;
    }

    private static String body(ClientHttpRequest request) {
        return ((MockClientHttpRequest) request).getBodyAsString();
    }

    private static boolean await(CountDownLatch latch, long seconds) throws IOException {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            // JDK HttpClient처럼 인터럽트 상태를 유지한 채 I/O 예외로 끝낸다.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record Fixture(OpenAiModerationClient client, MockRestServiceServer server) {
    }

    @Test
    void splitRequestsByImageLimitKeepsOneImagePerRequest() {
        ModerationInput text = ModerationInput.text("video post");
        ModerationInput firstFrame = ModerationInput.image("data:image/jpeg;base64,first");
        ModerationInput middleFrame = ModerationInput.image("data:image/jpeg;base64,middle");
        ModerationInput lastFrame = ModerationInput.image("data:image/jpeg;base64,last");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(
                text,
                firstFrame,
                middleFrame,
                lastFrame
        ));

        assertThat(requests).hasSize(3);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request).contains(text);
            assertThat(request)
                    .filteredOn(input -> "image_url".equals(input.type()))
                    .hasSize(1);
        });
        assertThat(requests.get(0)).containsExactly(text, firstFrame);
        assertThat(requests.get(1)).containsExactly(text, middleFrame);
        assertThat(requests.get(2)).containsExactly(text, lastFrame);
    }

    @Test
    void splitRequestsByImageLimitLeavesTextOnlyRequestUntouched() {
        ModerationInput text = ModerationInput.text("text only");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(text));

        assertThat(requests).containsExactly(List.of(text));
    }

    @Test
    void splitRequestsByImageLimitLeavesSingleImageRequestUntouched() {
        ModerationInput text = ModerationInput.text("image post");
        ModerationInput image = ModerationInput.image("data:image/jpeg;base64,image");

        List<List<ModerationInput>> requests = OpenAiModerationClient.splitRequestsByImageLimit(List.of(text, image));

        assertThat(requests).containsExactly(List.of(text, image));
    }
}
