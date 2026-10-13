package com.gamerin.backend.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.payment.client.KakaoPayClient;
import com.gamerin.backend.domain.payment.client.KakaoPayException;
import com.gamerin.backend.domain.payment.dto.request.PaymentApproveRequest;
import com.gamerin.backend.domain.payment.dto.request.PaymentReadyRequest;
import com.gamerin.backend.domain.payment.dto.response.PaymentResponse;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.payment.scheduler.PaymentRecoveryScheduler;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.response.CursorPageResponse;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "kakaopay.cancel-enabled=true",
                // 테스트가 스케줄러를 직접 호출한다.
                "app.payment.recovery.initial-delay-ms=3600000"
        }
)
@ActiveProfiles("test")
class PaymentRecoveryCancelListTest {

    private static final String CID = "TC0ONETIME";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRecoveryScheduler recoveryScheduler;
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
    @MockitoSpyBean
    private MileageService mileageService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // 스케줄러는 모든 주문을 훑으므로 다른 테스트가 남긴 주문을 지운다(테스트 전용 H2).
        jdbcTemplate.update("delete from payments");
        userId = createUser();
        when(kakaoPayClient.isConfigured()).thenReturn(true);
        when(kakaoPayClient.cid()).thenReturn(CID);
        stubReadySuccess();
    }

    private void stubReadySuccess() {
        doAnswer(invocation -> {
            String tid = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
            return new KakaoPayClient.ReadyResponse(tid, "https://pc/" + tid, "https://mobile/" + tid,
                    "2026-10-10T12:00:00");
        }).when(kakaoPayClient).ready(any());
    }

    // ---- 조회 시점 복구 ----

    @Test
    void staleApprovingIsRecoveredOnGetAndCreditedOnce() {
        UUID orderId = approvingAfterTimeout("CHARGE_10000");
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "SUCCESS_PAYMENT", 10_000L, null));

        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.APPROVING);
        verify(kakaoPayClient, never()).order(any());

        age(orderId);
        PaymentResponse recovered = paymentService.getPayment(userId, orderId);
        PaymentResponse again = paymentService.getPayment(userId, orderId);

        assertThat(recovered.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(again.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(kakaoPayClient, times(1)).order(any());
        assertThat(balance()).isEqualTo(10_000L);
        assertThat(ledgerRows(orderId, TransactionType.CHARGE)).isEqualTo(1);
    }

    @Test
    void approveRetryOnStaleApprovingRecoversWithoutCallingApproveAgain() {
        UUID orderId = approvingAfterTimeout("CHARGE_5000");
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "SUCCESS_PAYMENT", 5_000L, null));
        age(orderId);

        PaymentResponse response = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        verify(kakaoPayClient, times(1)).approve(any());
        assertThat(balance()).isEqualTo(5_000L);
    }

    @Test
    void dbFailureAfterApprovalIsRecoveredByOrderLookup() {
        UUID orderId = readyOrder("CHARGE_5000");
        when(kakaoPayClient.approve(any())).thenReturn(approveResponse(orderId, 5_000L));
        doThrow(new IllegalStateException("db down")).doCallRealMethod()
                .when(mileageService).addMileage(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status(orderId)).isEqualTo(PaymentStatus.APPROVING);
        assertThat(balance()).isZero();

        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "SUCCESS_PAYMENT", 5_000L, null));
        age(orderId);
        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(balance()).isEqualTo(5_000L);
        assertThat(ledgerRows(orderId, TransactionType.CHARGE)).isEqualTo(1);
    }

    @Test
    void staleApprovingWithFailedOrderBecomesFailed() {
        UUID orderId = approvingAfterTimeout("CHARGE_5000");
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "QUIT_PAYMENT", 5_000L, null));
        age(orderId);

        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(balance()).isZero();
    }

    @Test
    void staleApprovingStillInProgressAfterReadyExpiryBecomesExpired() {
        UUID orderId = approvingAfterTimeout("CHARGE_5000");
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "AUTH_PASSWORD", 5_000L, null));
        age(orderId);

        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.APPROVING);

        expireReady(orderId);
        age(orderId);
        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void lookupFailureKeepsApproving() {
        UUID orderId = approvingAfterTimeout("CHARGE_5000");
        when(kakaoPayClient.order(any())).thenThrow(unknown("order"));
        age(orderId);

        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.APPROVING);
    }

    @Test
    void lookupConfigurationErrorReturns503AndKeepsState() {
        UUID orderId = approvingAfterTimeout("CHARGE_5000");
        when(kakaoPayClient.order(any())).thenThrow(
                new KakaoPayException("order", KakaoPayException.Kind.CONFIGURATION, 401, -401, null));
        age(orderId);

        assertStatus(() -> paymentService.getPayment(userId, orderId), HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status(orderId)).isEqualTo(PaymentStatus.APPROVING);
    }

    // ---- 복구 스케줄러 ----

    @Test
    void schedulerClosesStaleOrders() {
        UUID created = createdOrder();
        UUID quit = readyOrder("CHARGE_5000");
        UUID expired = readyOrder("CHARGE_5000");
        UUID unreachable = readyOrder("CHARGE_5000");
        UUID fresh = readyOrder("CHARGE_5000");
        jdbcTemplate.update("update payments set status_changed_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(2), created);
        expireReady(quit);
        expireReady(expired);
        expireReady(unreachable);
        when(kakaoPayClient.order(any())).thenAnswer(invocation -> {
            KakaoPayClient.OrderRequest request = invocation.getArgument(0);
            if (request.tid().equals(tid(unreachable))) {
                throw unknown("order");
            }
            String status = request.tid().equals(tid(quit)) ? "QUIT_PAYMENT" : "READY";
            return new KakaoPayClient.OrderResponse(request.tid(), CID, status, null, null, null,
                    null, null, null, null, null, List.of());
        });

        recoveryScheduler.recoverStalePayments();

        assertThat(status(created)).isEqualTo(PaymentStatus.FAILED);
        assertThat(status(quit)).isEqualTo(PaymentStatus.FAILED);
        assertThat(status(expired)).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(status(unreachable)).isEqualTo(PaymentStatus.READY);
        assertThat(status(fresh)).isEqualTo(PaymentStatus.READY);
        verify(kakaoPayClient, times(3)).order(any());

        // 조회 실패한 주문은 다음 주기에 다시 시도한다.
        recoveryScheduler.recoverStalePayments();
        verify(kakaoPayClient, times(4)).order(any());
    }

    @Test
    void schedulerRecoversCancelingByRetryingCancel() {
        UUID orderId = approvedOrder("CHARGE_5000");
        when(kakaoPayClient.cancel(any())).thenThrow(unknown("cancel"))
                .thenReturn(cancelResponse(orderId, 5_000L));

        assertThat(paymentService.cancel(userId, orderId).status()).isEqualTo(PaymentStatus.CANCELING);
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "SUCCESS_PAYMENT", 5_000L, null));
        age(orderId);
        recoveryScheduler.recoverStalePayments();

        assertThat(status(orderId)).isEqualTo(PaymentStatus.CANCELED);
        verify(kakaoPayClient, times(2)).cancel(any());
        assertThat(ledgerRows(orderId, TransactionType.CHARGE_CANCEL)).isEqualTo(1);
        assertThat(balance()).isZero();
    }

    // ---- 결제 취소 ----

    @Test
    void cancelRevokesChargeOnceAndReplayDoesNotCallAgain() {
        UUID orderId = approvedOrder("CHARGE_10000");
        when(kakaoPayClient.cancel(any())).thenReturn(cancelResponse(orderId, 10_000L));

        PaymentResponse canceled = paymentService.cancel(userId, orderId);
        PaymentResponse replay = paymentService.cancel(userId, orderId);

        assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.canceledAt()).isNotNull();
        assertThat(replay.status()).isEqualTo(PaymentStatus.CANCELED);
        verify(kakaoPayClient, times(1)).cancel(any());
        assertThat(balance()).isZero();
        assertThat(ledgerRows(orderId, TransactionType.CHARGE_CANCEL)).isEqualTo(1);
        assertThat(paymentRepository.findById(orderId).orElseThrow().getCanceledAid()).isEqualTo("C-" + orderId);

        ArgumentCaptor<KakaoPayClient.CancelRequest> captor = ArgumentCaptor.forClass(KakaoPayClient.CancelRequest.class);
        verify(kakaoPayClient).cancel(captor.capture());
        assertThat(captor.getValue().cancelAmount()).isEqualTo(10_000L);
        assertThat(captor.getValue().cancelTaxFreeAmount()).isZero();
        assertThat(captor.getValue().cancelAvailableAmount()).isEqualTo(10_000L);
    }

    @Test
    void cancelIsRejectedWhenChargedMileageWasUsed() {
        UUID orderId = approvedOrder("CHARGE_10000");
        User user = userRepository.findById(userId).orElseThrow();
        mileageService.useMileage(user, 1L, TransactionType.MENTORING_PAY, "test", null);

        assertStatus(() -> paymentService.cancel(userId, orderId), HttpStatus.CONFLICT);
        assertThat(status(orderId)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(ledgerRows(orderId, TransactionType.CHARGE_CANCEL)).isZero();
        assertThat(balance()).isEqualTo(9_999L);
        verify(kakaoPayClient, never()).cancel(any());
    }

    @Test
    void cancelRequiresOwnerFeatureFlagAndMatchingCid() {
        UUID orderId = approvedOrder("CHARGE_5000");

        assertStatus(() -> paymentService.cancel(createUser(), orderId), HttpStatus.FORBIDDEN);

        ReflectionTestUtils.setField(paymentService, "cancelEnabled", false);
        try {
            assertStatus(() -> paymentService.cancel(userId, orderId), HttpStatus.FORBIDDEN);
        } finally {
            ReflectionTestUtils.setField(paymentService, "cancelEnabled", true);
        }

        when(kakaoPayClient.cid()).thenReturn("CID_CHANGED");
        assertStatus(() -> paymentService.cancel(userId, orderId), HttpStatus.FORBIDDEN);

        verify(kakaoPayClient, never()).cancel(any());
        assertThat(status(orderId)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void cancelNotApprovedOrderIsConflict() {
        UUID orderId = readyOrder("CHARGE_5000");

        assertStatus(() -> paymentService.cancel(userId, orderId), HttpStatus.CONFLICT);
    }

    @Test
    void cancelResultUnknownKeepsCancelingAndRecoversFromOrder() {
        UUID orderId = approvedOrder("CHARGE_5000");
        when(kakaoPayClient.cancel(any())).thenThrow(unknown("cancel"));

        assertThat(paymentService.cancel(userId, orderId).status()).isEqualTo(PaymentStatus.CANCELING);
        // 회수는 외부 취소 전에 선반영된다.
        assertThat(balance()).isZero();
        assertThat(paymentService.cancel(userId, orderId).status()).isEqualTo(PaymentStatus.CANCELING);
        verify(kakaoPayClient, times(1)).cancel(any());

        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "CANCEL_PAYMENT", 5_000L, 5_000L));
        age(orderId);
        assertThat(paymentService.getPayment(userId, orderId).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(ledgerRows(orderId, TransactionType.CHARGE_CANCEL)).isEqualTo(1);
    }

    @Test
    void cancelRejectedButAlreadyCanceledBecomesCanceled() {
        UUID orderId = approvedOrder("CHARGE_5000");
        when(kakaoPayClient.cancel(any())).thenThrow(
                new KakaoPayException("cancel", KakaoPayException.Kind.REJECTED, 400, -785, null));
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "CANCEL_PAYMENT", 5_000L, 5_000L));

        assertThat(paymentService.cancel(userId, orderId).status()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void cancelRejectedAndStillPaidGoesToReview() {
        UUID orderId = approvedOrder("CHARGE_5000");
        when(kakaoPayClient.cancel(any())).thenThrow(
                new KakaoPayException("cancel", KakaoPayException.Kind.REJECTED, 400, -1, null));
        when(kakaoPayClient.order(any())).thenReturn(order(orderId, "SUCCESS_PAYMENT", 5_000L, null));

        assertThat(paymentService.cancel(userId, orderId).status()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        // 회수를 자동으로 되돌리지 않고 수동 확인에 맡긴다.
        assertThat(ledgerRows(orderId, TransactionType.CHARGE_CANCEL)).isEqualTo(1);
    }

    @Test
    void cancelConfigurationErrorReturns503AndKeepsCanceling() {
        UUID orderId = approvedOrder("CHARGE_5000");
        when(kakaoPayClient.cancel(any())).thenThrow(
                new KakaoPayException("cancel", KakaoPayException.Kind.CONFIGURATION, 400, -731, null));

        assertStatus(() -> paymentService.cancel(userId, orderId), HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status(orderId)).isEqualTo(PaymentStatus.CANCELING);
    }

    // ---- 결제 목록 ----

    @Test
    void listReturnsOnlyOwnPaidOrdersNewestFirstWithCursor() {
        UUID first = approvedOrder("CHARGE_5000");
        UUID second = approvedOrder("CHARGE_10000");
        UUID third = approvingAfterTimeout("CHARGE_30000");
        readyOrder("CHARGE_5000");
        createdOrder();
        UUID otherUser = createUser();
        UUID mine = userId;
        userId = otherUser;
        approvedOrder("CHARGE_50000");
        userId = mine;

        CursorPageResponse<PaymentResponse> page1 = paymentService.getMyPayments(userId, null, 2);
        CursorPageResponse<PaymentResponse> page2 = paymentService.getMyPayments(userId, page1.nextCursor(), 2);

        assertThat(page1.items()).extracting(PaymentResponse::orderId).containsExactly(third, second);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page2.items()).extracting(PaymentResponse::orderId).containsExactly(first);
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
        assertStatus(() -> paymentService.getMyPayments(userId, "broken", 2), HttpStatus.BAD_REQUEST);
    }

    // ---- 헬퍼 ----

    private UUID createdOrder() {
        // ready 호출 중 프로세스가 멈춰 CREATED로 남은 주문
        doThrow(new IllegalStateException("process stopped")).when(kakaoPayClient).ready(any());
        UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId)))
                .isInstanceOf(IllegalStateException.class);
        stubReadySuccess();
        return paymentRepository.findByBuyerIdAndRequestId(userId, requestId).orElseThrow().getId();
    }

    private UUID readyOrder(String productCode) {
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest(productCode, UUID.randomUUID())).orderId();
        assertThat(status(orderId)).isEqualTo(PaymentStatus.READY);
        return orderId;
    }

    private UUID approvedOrder(String productCode) {
        UUID orderId = readyOrder(productCode);
        long amount = paymentRepository.findById(orderId).orElseThrow().getAmount();
        doReturn(approveResponse(orderId, amount)).when(kakaoPayClient).approve(any());
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVED);
        return orderId;
    }

    // 승인 응답 유실(타임아웃)로 APPROVING에 남은 주문
    private UUID approvingAfterTimeout(String productCode) {
        UUID orderId = readyOrder(productCode);
        doThrow(unknown("approve")).when(kakaoPayClient).approve(any());
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVING);
        return orderId;
    }

    private KakaoPayClient.ApproveResponse approveResponse(UUID orderId, long total) {
        return new KakaoPayClient.ApproveResponse("A-" + orderId, tid(orderId), CID, orderId.toString(),
                userId.toString(), "MONEY", new KakaoPayClient.Amount(total), "2026-10-10T12:01:00");
    }

    private KakaoPayClient.CancelResponse cancelResponse(UUID orderId, long total) {
        return new KakaoPayClient.CancelResponse("C-" + orderId, tid(orderId), CID, "CANCEL_PAYMENT",
                new KakaoPayClient.Amount(total), "2026-10-10T12:05:00");
    }

    private KakaoPayClient.OrderResponse order(UUID orderId, String status, long total, Long canceled) {
        List<KakaoPayClient.PaymentActionDetail> details = canceled == null
                ? List.of(new KakaoPayClient.PaymentActionDetail("A-" + orderId, "2026-10-10T12:01:00", "PAYMENT"))
                : List.of(new KakaoPayClient.PaymentActionDetail("A-" + orderId, "2026-10-10T12:01:00", "PAYMENT"),
                        new KakaoPayClient.PaymentActionDetail("C-" + orderId, "2026-10-10T12:05:00", "CANCEL"));
        return new KakaoPayClient.OrderResponse(tid(orderId), CID, status, orderId.toString(), userId.toString(),
                "CARD", new KakaoPayClient.Amount(total),
                canceled == null ? null : new KakaoPayClient.Amount(canceled), null,
                "2026-10-10T12:01:00", canceled == null ? null : "2026-10-10T12:05:00", details);
    }

    private static KakaoPayException unknown(String operation) {
        return new KakaoPayException(operation, KakaoPayException.Kind.UNKNOWN, null, null, null);
    }

    private String tid(UUID orderId) {
        return paymentRepository.findById(orderId).orElseThrow().getTid();
    }

    private PaymentStatus status(UUID orderId) {
        return paymentRepository.findById(orderId).orElseThrow().getStatus();
    }

    // 결과 불명 판정 기준(1분)을 넘긴다.
    private void age(UUID orderId) {
        jdbcTemplate.update("update payments set status_changed_at = status_changed_at - interval '2' minute where id = ?",
                orderId);
    }

    private void expireReady(UUID orderId) {
        jdbcTemplate.update("update payments set ready_at = ? where id = ?", OffsetDateTime.now().minusMinutes(16), orderId);
    }

    private long balance() {
        Long balance = jdbcTemplate.query("select balance from mileage_wallets where user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return balance == null ? 0L : balance;
    }

    private long ledgerRows(UUID orderId, TransactionType type) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from mileage_transactions where type = ? and reference_id = ?",
                Long.class, type.name(), orderId);
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
