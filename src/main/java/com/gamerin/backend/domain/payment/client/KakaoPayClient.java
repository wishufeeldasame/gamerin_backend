package com.gamerin.backend.domain.payment.client;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 카카오페이 온라인 단건 결제 API 클라이언트 (04 문서 6-3·6-5절).
 * 신규 API(open-api.kakaopay.com + SECRET_KEY + JSON)만 사용한다.
 * Secret key·pg_token·결제창 URL·error_message는 로그에 남기지 않고 오류 코드만 기록한다.
 */
@Component
public class KakaoPayClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoPayClient.class);

    private static final String READY_PATH = "/online/v1/payment/ready";
    private static final String APPROVE_PATH = "/online/v1/payment/approve";
    private static final String ORDER_PATH = "/online/v1/payment/order";
    private static final String CANCEL_PATH = "/online/v1/payment/cancel";
    // 응답 시각은 오프셋 없는 yyyy-MM-ddTHH:mm:ss다. KST로 해석한다(확인 필요: 공식 문서에 시간대 명시 없음).
    private static final ZoneId KAKAOPAY_ZONE = ZoneId.of("Asia/Seoul");
    // -401 Secret key 오류, -403 사용 API 미등록, -429 쿼터 초과, -731 잘못된 CID
    private static final Set<Integer> CONFIGURATION_ERROR_CODES = Set.of(-401, -403, -429, -731);

    private final RestClient restClient;
    private final String cid;
    private final boolean configured;

    public KakaoPayClient(
            @Value("${kakaopay.api.base-url:https://open-api.kakaopay.com}") String baseUrl,
            @Value("${kakaopay.api.secret-key:}") String secretKey,
            @Value("${kakaopay.cid:}") String cid,
            @Value("${kakaopay.api.connect-timeout:3s}") Duration connectTimeout,
            @Value("${kakaopay.api.read-timeout:15s}") Duration readTimeout
    ) {
        String configuredBaseUrl = trimToNull(baseUrl);
        String configuredSecretKey = trimToNull(secretKey);
        this.cid = trimToNull(cid);
        this.configured = configuredBaseUrl != null && configuredSecretKey != null && this.cid != null;

        ClientHttpRequestFactorySettings requestFactorySettings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(requestFactorySettings))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (configuredBaseUrl != null) {
            builder.baseUrl(configuredBaseUrl);
        }
        if (configuredSecretKey != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "SECRET_KEY " + configuredSecretKey);
        }
        this.restClient = builder.build();
    }

    /** Secret key·CID가 모두 설정됐는지. 비어 있으면 결제 준비를 거부한다. */
    public boolean isConfigured() {
        return configured;
    }

    /** 허용하는 유일한 CID(kakaopay.cid). 요청·응답·저장 CID를 이 값과 대조한다. */
    public String cid() {
        return cid;
    }

    public ReadyResponse ready(ReadyRequest request) {
        ReadyResponse response = post("ready", READY_PATH, request, ReadyResponse.class);
        if (trimToNull(response.tid()) == null
                || trimToNull(response.nextRedirectPcUrl()) == null
                || trimToNull(response.nextRedirectMobileUrl()) == null) {
            throw failure("ready", KakaoPayException.Kind.UNKNOWN, null, null, null);
        }
        return response;
    }

    public ApproveResponse approve(ApproveRequest request) {
        return post("approve", APPROVE_PATH, request, ApproveResponse.class);
    }

    public OrderResponse order(OrderRequest request) {
        OrderResponse response = post("order", ORDER_PATH, request, OrderResponse.class);
        if (trimToNull(response.status()) == null) {
            throw failure("order", KakaoPayException.Kind.UNKNOWN, null, null, null);
        }
        return response;
    }

    public CancelResponse cancel(CancelRequest request) {
        return post("cancel", CANCEL_PATH, request, CancelResponse.class);
    }

    /** 카카오페이 응답 시각(오프셋 없음)을 KST로 해석한다. 비었거나 형식이 다르면 null. */
    public static OffsetDateTime toOffsetDateTime(String value) {
        if (trimToNull(value) == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip()).atZone(KAKAOPAY_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private <T> T post(String operation, String path, Object body, Class<T> responseType) {
        if (!configured) {
            throw failure(operation, KakaoPayException.Kind.CONFIGURATION, null, null, null);
        }
        T response;
        try {
            response = restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException e) {
            throw classify(operation, e);
        } catch (RestClientException e) {
            // 타임아웃·연결 끊김·본문 해석 실패
            throw failure(operation, KakaoPayException.Kind.UNKNOWN, null, null, null);
        }
        if (response == null) {
            throw failure(operation, KakaoPayException.Kind.UNKNOWN, null, null, null);
        }
        return response;
    }

    private KakaoPayException classify(String operation, RestClientResponseException e) {
        ErrorResponse error = readError(e);
        Integer errorCode = error == null ? null : error.errorCode();
        String methodResultCode = error == null || error.extras() == null ? null : error.extras().methodResultCode();
        int status = e.getStatusCode().value();

        KakaoPayException.Kind kind;
        if (errorCode != null && CONFIGURATION_ERROR_CODES.contains(errorCode)) {
            kind = KakaoPayException.Kind.CONFIGURATION;
        } else if (e.getStatusCode().is4xxClientError()) {
            kind = KakaoPayException.Kind.REJECTED;
        } else {
            kind = KakaoPayException.Kind.UNKNOWN;
        }
        return failure(operation, kind, status, errorCode, methodResultCode);
    }

    private ErrorResponse readError(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(ErrorResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private KakaoPayException failure(String operation, KakaoPayException.Kind kind, Integer httpStatus,
                                      Integer errorCode, String methodResultCode) {
        if (kind == KakaoPayException.Kind.CONFIGURATION) {
            log.error("KakaoPay {} configuration error: httpStatus={}, errorCode={}, methodResultCode={}",
                    operation, httpStatus, errorCode, methodResultCode);
        } else {
            log.warn("KakaoPay {} failed: kind={}, httpStatus={}, errorCode={}, methodResultCode={}",
                    operation, kind, httpStatus, errorCode, methodResultCode);
        }
        return new KakaoPayException(operation, kind, httpStatus, errorCode, methodResultCode);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- 요청·응답 규격 (04 문서 6-5절). 사용하지 않는 응답 필드는 무시한다. ----

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReadyRequest(
            String cid,
            String partnerOrderId,
            String partnerUserId,
            String itemName,
            String itemCode,
            int quantity,
            long totalAmount,
            long taxFreeAmount,
            String approvalUrl,
            String cancelUrl,
            String failUrl
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadyResponse(
            String tid,
            String nextRedirectPcUrl,
            String nextRedirectMobileUrl,
            String createdAt
    ) {
        @Override
        public String toString() {
            // 결제창 URL을 로그에 남기지 않는다.
            return "ReadyResponse[tid=" + tid + "]";
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ApproveRequest(
            String cid,
            String tid,
            String partnerOrderId,
            String partnerUserId,
            String pgToken,
            long totalAmount
    ) {
        @Override
        public String toString() {
            // pg_token을 로그에 남기지 않는다.
            return "ApproveRequest[tid=" + tid + ", partnerOrderId=" + partnerOrderId + "]";
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproveResponse(
            String aid,
            String tid,
            String cid,
            String partnerOrderId,
            String partnerUserId,
            String paymentMethodType,
            Amount amount,
            String approvedAt
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OrderRequest(String cid, String tid) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(
            String tid,
            String cid,
            String status,
            String partnerOrderId,
            String partnerUserId,
            String paymentMethodType,
            Amount amount,
            Amount canceledAmount,
            Amount cancelAvailableAmount,
            String approvedAt,
            String canceledAt,
            List<PaymentActionDetail> paymentActionDetails
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentActionDetail(
            String aid,
            String approvedAt,
            String paymentActionType
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CancelRequest(
            String cid,
            String tid,
            long cancelAmount,
            long cancelTaxFreeAmount,
            long cancelAvailableAmount
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelResponse(
            String aid,
            String tid,
            String cid,
            String status,
            Amount approvedCancelAmount,
            String canceledAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long total) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(Integer errorCode, Extras extras) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extras(String methodResultCode) {
    }
}
