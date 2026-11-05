package com.gamerin.backend.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class KakaoPayClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long responseDelayMs;
    private volatile String requestPath;
    private volatile String requestAuthorization;
    private volatile String requestContentType;
    private volatile String requestBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readySendsSecretKeyHeaderAndSnakeCaseBody() throws Exception {
        respond(200, """
                {"tid":"T1","next_redirect_pc_url":"https://pc","next_redirect_mobile_url":"https://mobile",
                 "next_redirect_app_url":"https://app","created_at":"2026-10-10T12:00:00"}
                """);

        KakaoPayClient.ReadyResponse response = client(Duration.ofSeconds(5)).ready(new KakaoPayClient.ReadyRequest(
                "TC0ONETIME", "order-1", "user-1", "마일리지 5,000", "CHARGE_5000", 1, 5000, 0,
                "http://localhost:3000/payments/result?orderId=order-1&result=approval",
                "http://localhost:3000/payments/result?orderId=order-1&result=cancel",
                "http://localhost:3000/payments/result?orderId=order-1&result=fail"
        ));

        assertThat(response.tid()).isEqualTo("T1");
        assertThat(response.nextRedirectPcUrl()).isEqualTo("https://pc");
        assertThat(requestPath).isEqualTo("/online/v1/payment/ready");
        assertThat(requestAuthorization).isEqualTo("SECRET_KEY test-secret");
        assertThat(requestContentType).startsWith("application/json");
        JsonNode body = objectMapper.readTree(requestBody);
        assertThat(body.get("cid").asText()).isEqualTo("TC0ONETIME");
        assertThat(body.get("partner_order_id").asText()).isEqualTo("order-1");
        assertThat(body.get("partner_user_id").asText()).isEqualTo("user-1");
        assertThat(body.get("item_code").asText()).isEqualTo("CHARGE_5000");
        assertThat(body.get("quantity").asInt()).isEqualTo(1);
        assertThat(body.get("total_amount").asLong()).isEqualTo(5000);
        assertThat(body.has("tax_free_amount")).isTrue();
        assertThat(body.get("tax_free_amount").asLong()).isZero();
        assertThat(body.get("approval_url").asText()).endsWith("result=approval");
    }

    @Test
    void readyWithoutTidIsUnknown() {
        respond(200, "{\"next_redirect_pc_url\":\"https://pc\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).ready(readyRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class,
                        e -> assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.UNKNOWN));
    }

    @Test
    void approve4xxIsRejectedWithParsedCodes() {
        respond(400, """
                {"error_code":-780,"error_message":"approval failure!",
                 "extras":{"method_result_code":"USER_LOCKED","method_result_message":"secret"}}
                """);

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).approve(approveRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.REJECTED);
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo(-780);
                    assertThat(e.getMethodResultCode()).isEqualTo("USER_LOCKED");
                    assertThat(e.getMessage()).doesNotContain("approval failure", "secret");
                });
    }

    @Test
    void configurationErrorCodesAreClassifiedAsConfiguration() {
        respond(400, "{\"error_code\":-731,\"error_message\":\"invalid cid\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).approve(approveRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class,
                        e -> assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.CONFIGURATION));
    }

    @Test
    void serverErrorIsUnknown() {
        respond(500, "{\"error_code\":-500,\"error_message\":\"internal\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).approve(approveRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.UNKNOWN);
                    assertThat(e.getErrorCode()).isEqualTo(-500);
                });
    }

    @Test
    void readTimeoutIsUnknown() {
        respond(200, "{\"aid\":\"A1\"}");
        responseDelayMs = 1_000;

        assertThatThrownBy(() -> client(Duration.ofMillis(200)).approve(approveRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class,
                        e -> assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.UNKNOWN));
    }

    @Test
    void missingConfigurationFailsWithoutCallingKakaoPay() {
        KakaoPayClient client = new KakaoPayClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), " ", "TC0ONETIME",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.ready(readyRequest()))
                .isInstanceOfSatisfying(KakaoPayException.class,
                        e -> assertThat(e.getKind()).isEqualTo(KakaoPayException.Kind.CONFIGURATION));
        assertThat(requestPath).isNull();
    }

    @Test
    void orderParsesStatusAndActionDetails() {
        respond(200, """
                {"tid":"T1","cid":"TC0ONETIME","status":"SUCCESS_PAYMENT","partner_order_id":"order-1",
                 "partner_user_id":"user-1","payment_method_type":"MONEY","amount":{"total":5000,"vat":455},
                 "approved_at":"2026-10-10T12:01:00",
                 "payment_action_details":[{"aid":"A1","payment_action_type":"PAYMENT","amount":5000}]}
                """);

        KakaoPayClient.OrderResponse order = client(Duration.ofSeconds(5))
                .order(new KakaoPayClient.OrderRequest("TC0ONETIME", "T1"));

        assertThat(order.status()).isEqualTo("SUCCESS_PAYMENT");
        assertThat(order.amount().total()).isEqualTo(5000L);
        assertThat(order.paymentActionDetails()).singleElement()
                .satisfies(detail -> {
                    assertThat(detail.aid()).isEqualTo("A1");
                    assertThat(detail.paymentActionType()).isEqualTo("PAYMENT");
                });
        assertThat(requestPath).isEqualTo("/online/v1/payment/order");
    }

    @Test
    void kakaoPayTimesAreInterpretedAsKst() {
        assertThat(KakaoPayClient.toOffsetDateTime("2026-10-10T12:00:00"))
                .isEqualTo(OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(KakaoPayClient.toOffsetDateTime(null)).isNull();
        assertThat(KakaoPayClient.toOffsetDateTime("not-a-time")).isNull();
    }

    private void respond(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    private KakaoPayClient client(Duration readTimeout) {
        return new KakaoPayClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-secret",
                "TC0ONETIME",
                Duration.ofSeconds(1),
                readTimeout
        );
    }

    private KakaoPayClient.ReadyRequest readyRequest() {
        return new KakaoPayClient.ReadyRequest("TC0ONETIME", "order-1", "user-1", "item", "CHARGE_5000",
                1, 5000, 0, "a", "c", "f");
    }

    private KakaoPayClient.ApproveRequest approveRequest() {
        return new KakaoPayClient.ApproveRequest("TC0ONETIME", "T1", "order-1", "user-1", "pg-token", 5000);
    }
}
