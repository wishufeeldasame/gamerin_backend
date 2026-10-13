package com.gamerin.backend.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.payment.client.KakaoPayClient;
import com.gamerin.backend.domain.payment.client.KakaoPayException;
import com.gamerin.backend.domain.payment.dto.request.PaymentApproveRequest;
import com.gamerin.backend.domain.payment.dto.request.PaymentReadyRequest;
import com.gamerin.backend.domain.payment.dto.response.PaymentProductResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentReadyResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentResponse;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.payment.recovery.initial-delay-ms=3600000"
)
@ActiveProfiles("test")
class PaymentServiceTest {

    private static final String CID = "TC0ONETIME";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KakaoPayClient kakaoPayClient;

    private UUID userId;
    private String tid;

    @BeforeEach
    void setUp() {
        userId = createUser();
        tid = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
        when(kakaoPayClient.isConfigured()).thenReturn(true);
        when(kakaoPayClient.cid()).thenReturn(CID);
        when(kakaoPayClient.ready(any())).thenReturn(new KakaoPayClient.ReadyResponse(
                tid, "https://pc.example/" + tid, "https://mobile.example/" + tid, "2026-10-10T12:00:00"));
    }

    @Test
    void productsComeFromServerDefinition() {
        List<PaymentProductResponse> products = paymentService.getProducts();

        assertThat(products).extracting(PaymentProductResponse::productCode)
                .containsExactly("CHARGE_5000", "CHARGE_10000", "CHARGE_30000", "CHARGE_50000", "MEMBERSHIP_30D");
        assertThat(products).filteredOn(p -> p.productCode().startsWith("CHARGE_"))
                .allSatisfy(p -> assertThat(p.mileage()).isEqualTo(p.price()));
        assertThat(products).filteredOn(p -> p.productCode().equals("MEMBERSHIP_30D")).singleElement()
                .satisfies(p -> {
                    assertThat(p.price()).isEqualTo(4_900L);
                    assertThat(p.durationDays()).isEqualTo(30);
                    assertThat(p.mileage()).isNull();
                });
    }

    @Test
    void readyUsesServerPriceAndResultUrls() {
        PaymentReadyResponse response = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_10000", UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(PaymentStatus.READY);
        assertThat(response.pcUrl()).isEqualTo("https://pc.example/" + tid);
        assertThat(response.mobileUrl()).isEqualTo("https://mobile.example/" + tid);

        ArgumentCaptor<KakaoPayClient.ReadyRequest> captor = ArgumentCaptor.forClass(KakaoPayClient.ReadyRequest.class);
        verify(kakaoPayClient).ready(captor.capture());
        KakaoPayClient.ReadyRequest sent = captor.getValue();
        String orderId = response.orderId().toString();
        assertThat(sent.cid()).isEqualTo(CID);
        assertThat(sent.partnerOrderId()).isEqualTo(orderId);
        assertThat(sent.partnerUserId()).isEqualTo(userId.toString());
        assertThat(sent.totalAmount()).isEqualTo(10_000L);
        assertThat(sent.taxFreeAmount()).isZero();
        assertThat(sent.quantity()).isEqualTo(1);
        assertThat(sent.approvalUrl())
                .isEqualTo("http://localhost:3000/payments/result?orderId=" + orderId + "&result=approval");
        assertThat(sent.cancelUrl()).endsWith("result=cancel");
        assertThat(sent.failUrl()).endsWith("result=fail");
        assertThat(paymentRepository.findById(response.orderId()).orElseThrow().getTid()).isEqualTo(tid);
    }

    @Test
    void readyRejectsUnknownProduct() {
        assertStatus(() -> paymentService.ready(userId, new PaymentReadyRequest("CHARGE_1", UUID.randomUUID())),
                HttpStatus.BAD_REQUEST);
        verify(kakaoPayClient, never()).ready(any());
    }

    @Test
    void readyRejectsMissingConfiguration() {
        when(kakaoPayClient.isConfigured()).thenReturn(false);

        UUID requestId = UUID.randomUUID();
        assertStatus(() -> paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId)),
                HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(paymentRepository.findByBuyerIdAndRequestId(userId, requestId)).isEmpty();
    }

    @Test
    void readyReplaySameRequestIdReturnsStoredOrderWithoutCallingKakaoPay() {
        UUID requestId = UUID.randomUUID();
        PaymentReadyResponse first = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId));

        PaymentReadyResponse replay = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId));

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.pcUrl()).isEqualTo(first.pcUrl());
        verify(kakaoPayClient, times(1)).ready(any());
        assertStatus(() -> paymentService.ready(userId, new PaymentReadyRequest("CHARGE_10000", requestId)),
                HttpStatus.CONFLICT);
    }

    @Test
    void readyReplayAfterExpiryHidesUrls() {
        UUID requestId = UUID.randomUUID();
        PaymentReadyResponse first = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId));
        expireReady(first.orderId());

        PaymentReadyResponse replay = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId));

        assertThat(replay.status()).isEqualTo(PaymentStatus.READY);
        assertThat(replay.pcUrl()).isNull();
        assertThat(replay.mobileUrl()).isNull();
    }

    @Test
    void readyFailureClosesOrderAsFailed() {
        when(kakaoPayClient.ready(any())).thenThrow(
                new KakaoPayException("ready", KakaoPayException.Kind.UNKNOWN, null, null, null));

        PaymentReadyResponse response = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.pcUrl()).isNull();
    }

    @Test
    void readyConfigurationErrorClosesOrderAndReturns503() {
        UUID requestId = UUID.randomUUID();
        when(kakaoPayClient.ready(any())).thenThrow(
                new KakaoPayException("ready", KakaoPayException.Kind.CONFIGURATION, 401, -401, null));

        assertStatus(() -> paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId)),
                HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(paymentRepository.findByBuyerIdAndRequestId(userId, requestId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void approveCreditsMileageOnceAndRefreshDoesNotRepeat() {
        UUID orderId = readyOrder("CHARGE_30000");
        when(kakaoPayClient.approve(any())).thenReturn(approveResponse(orderId, 30_000L));

        PaymentResponse approved = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));
        PaymentResponse refreshed = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approved.paymentMethodType()).isEqualTo("MONEY");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(refreshed.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(kakaoPayClient, times(1)).approve(any());
        assertThat(balance()).isEqualTo(30_000L);
        assertThat(chargeRows(orderId)).isEqualTo(1);

        ArgumentCaptor<KakaoPayClient.ApproveRequest> captor = ArgumentCaptor.forClass(KakaoPayClient.ApproveRequest.class);
        verify(kakaoPayClient).approve(captor.capture());
        assertThat(captor.getValue().totalAmount()).isEqualTo(30_000L);
        assertThat(captor.getValue().partnerUserId()).isEqualTo(userId.toString());
        assertThat(captor.getValue().tid()).isEqualTo(tid);
    }

    @Test
    void approveByOtherUserIsForbidden() {
        UUID orderId = readyOrder("CHARGE_5000");
        UUID otherUserId = createUser();

        assertStatus(() -> paymentService.approve(otherUserId, new PaymentApproveRequest(orderId, "pg-token")),
                HttpStatus.FORBIDDEN);
        assertStatus(() -> paymentService.getPayment(otherUserId, orderId), HttpStatus.FORBIDDEN);
        verify(kakaoPayClient, never()).approve(any());
    }

    @Test
    void missingOrderIsNotFound() {
        assertStatus(() -> paymentService.getPayment(userId, UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    void approvedAmountMismatchGoesToReviewWithoutCredit() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenReturn(approveResponse(orderId, 100L));

        PaymentResponse response = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(response.status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(balance()).isZero();
        assertThat(chargeRows(orderId)).isZero();
    }

    @Test
    void approveRejectedThenOrderLookupSuccessCreditsOnce() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.REJECTED, 400, -702, null));
        when(kakaoPayClient.order(any())).thenReturn(orderResponse(orderId, "SUCCESS_PAYMENT", 5_000L));

        PaymentResponse response = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(balance()).isEqualTo(5_000L);
        assertThat(chargeRows(orderId)).isEqualTo(1);
        assertThat(paymentRepository.findById(orderId).orElseThrow().getApprovedAid()).isEqualTo("A-" + orderId);
    }

    @Test
    void approveRejectedThenOrderLookupFailureClosesAsFailed() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.REJECTED, 400, -780, null));
        when(kakaoPayClient.order(any())).thenReturn(orderResponse(orderId, "FAIL_PAYMENT", 5_000L));

        PaymentResponse response = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(balance()).isZero();
    }

    @Test
    void approveRejectedThenOrderInProgressStaysApproving() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.REJECTED, 400, -780, "USER_LOCKED"));
        when(kakaoPayClient.order(any())).thenReturn(orderResponse(orderId, "AUTH_PASSWORD", 5_000L));

        PaymentResponse response = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVING);
    }

    @Test
    void approveTimeoutStaysApprovingAndRetryDoesNotCallAgain() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.UNKNOWN, null, null, null));

        PaymentResponse first = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));
        PaymentResponse retry = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(first.status()).isEqualTo(PaymentStatus.APPROVING);
        assertThat(retry.status()).isEqualTo(PaymentStatus.APPROVING);
        verify(kakaoPayClient, times(1)).approve(any());
        verify(kakaoPayClient, never()).order(any());
        assertThat(balance()).isZero();
    }

    @Test
    void approveConfigurationErrorKeepsApprovingAndReturns503() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.CONFIGURATION, 400, -731, null));

        assertStatus(() -> paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")),
                HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(paymentRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVING);
    }

    @Test
    void approveExpiredReadyOrderIsRejected() {
        UUID orderId = readyOrder("CHARGE_5000");
        expireReady(orderId);

        assertStatus(() -> paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")),
                HttpStatus.CONFLICT);
        verify(kakaoPayClient, never()).approve(any());
    }

    @Test
    void approveFailedOrderIsRejected() {
        when(kakaoPayClient.ready(any())).thenThrow(
                new KakaoPayException("ready", KakaoPayException.Kind.REJECTED, 400, -1, null));
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", UUID.randomUUID())).orderId();

        assertStatus(() -> paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")),
                HttpStatus.CONFLICT);
    }

    @Test
    void staleClaimDoesNotOverwriteNewerState() {
        UUID orderId = readyOrder("CHARGE_5000");
        OffsetDateTime observedAt = paymentRepository.findById(orderId).orElseThrow().getStatusChangedAt();
        OffsetDateTime now = OffsetDateTime.now();

        Integer first = transactionTemplate.execute(s -> paymentRepository.transition(
                orderId, PaymentStatus.READY, observedAt, PaymentStatus.APPROVING, now));
        Integer second = transactionTemplate.execute(s -> paymentRepository.transition(
                orderId, PaymentStatus.READY, observedAt, PaymentStatus.FAILED, now));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(paymentRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVING);
    }

    private UUID readyOrder(String productCode) {
        PaymentReadyResponse response = paymentService.ready(userId, new PaymentReadyRequest(productCode, UUID.randomUUID()));
        assertThat(response.status()).isEqualTo(PaymentStatus.READY);
        return response.orderId();
    }

    private KakaoPayClient.ApproveResponse approveResponse(UUID orderId, long total) {
        return new KakaoPayClient.ApproveResponse("A-" + orderId, tid, CID, orderId.toString(), userId.toString(),
                "MONEY", new KakaoPayClient.Amount(total), "2026-10-10T12:01:00");
    }

    private KakaoPayClient.OrderResponse orderResponse(UUID orderId, String status, long total) {
        return new KakaoPayClient.OrderResponse(tid, CID, status, orderId.toString(), userId.toString(), "CARD",
                new KakaoPayClient.Amount(total), null, null, "2026-10-10T12:01:00", null,
                List.of(new KakaoPayClient.PaymentActionDetail("A-" + orderId, "2026-10-10T12:01:00", "PAYMENT")));
    }

    private void expireReady(UUID orderId) {
        jdbcTemplate.update("update payments set ready_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(16), orderId);
    }

    private long balance() {
        Long balance = jdbcTemplate.query("select balance from mileage_wallets where user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return balance == null ? 0L : balance;
    }

    private long chargeRows(UUID orderId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from mileage_transactions where type = 'CHARGE' and reference_id = ?",
                Long.class, orderId);
        return count == null ? 0L : count;
    }

    private UUID createUser() {
        return transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            User user = User.createLocal("pay+" + suffix + "@example.com", "pay" + suffix, "pay", "encoded-password");
            user.setProfile(UserProfile.createDefault(user));
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status.value()));
    }
}
