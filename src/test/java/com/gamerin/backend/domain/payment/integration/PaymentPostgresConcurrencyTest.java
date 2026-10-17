package com.gamerin.backend.domain.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.payment.client.KakaoPayClient;
import com.gamerin.backend.domain.payment.dto.request.PaymentApproveRequest;
import com.gamerin.backend.domain.payment.dto.request.PaymentReadyRequest;
import com.gamerin.backend.domain.payment.dto.response.PaymentReadyResponse;
import com.gamerin.backend.domain.payment.dto.response.PaymentResponse;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.payment.client.KakaoPayException;
import com.gamerin.backend.domain.payment.scheduler.PaymentRecoveryScheduler;

import com.gamerin.backend.domain.payment.service.PaymentService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "PAYMENT_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "kakaopay.cancel-enabled=true",
                // 테스트가 복구를 직접 호출한다.
                "app.payment.recovery.initial-delay-ms=3600000"
        }
)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 6;
    private static final String CID = "TC0ONETIME";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(20);
    private static PostgresSchema postgresSchema;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentRecoveryScheduler recoveryScheduler;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KakaoPayClient kakaoPayClient;
    @MockitoSpyBean
    private MembershipService membershipService;

    private UUID userId;
    private String tid;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        postgresSchema = PostgresSchema.create();
        registry.add("spring.datasource.url", postgresSchema::url);
        registry.add("spring.datasource.username", postgresSchema::username);
        registry.add("spring.datasource.password", postgresSchema::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", postgresSchema::schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", postgresSchema::schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", postgresSchema::schema);
        registry.add("spring.flyway.default-schema", postgresSchema::schema);
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @BeforeEach
    void setUp() {
        userId = createUser();
        tid = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
        when(kakaoPayClient.isConfigured()).thenReturn(true);
        when(kakaoPayClient.cid()).thenReturn(CID);
        when(kakaoPayClient.ready(any())).thenAnswer(invocation -> {
            Thread.sleep(100);
            return new KakaoPayClient.ReadyResponse(tid, "https://pc", "https://mobile", "2026-10-10T12:00:00");
        });
    }

    @Test
    void concurrentApprovesCallKakaoPayOnceAndCreditOnce() throws Exception {
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_10000", UUID.randomUUID())).orderId();
        when(kakaoPayClient.approve(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return new KakaoPayClient.ApproveResponse("A1", tid, CID, orderId.toString(), userId.toString(),
                    "MONEY", new KakaoPayClient.Amount(10_000L), "2026-10-10T12:01:00");
        });

        List<Outcome<PaymentResponse>> outcomes = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<PaymentResponse>) () ->
                        paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")))
                .toList());

        assertNoFailures(outcomes);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value().status())
                .isIn(PaymentStatus.APPROVING, PaymentStatus.APPROVED));
        verify(kakaoPayClient, times(1)).approve(any());
        assertThat(paymentStatus(orderId)).isEqualTo("APPROVED");
        assertThat(chargeRows(orderId)).isEqualTo(1);
        assertThat(balance()).isEqualTo(10_000L);
    }

    @Test
    void membershipApprovalAndReplayPersistOneGrantAndThirtyDaysWithoutMileage() {
        UUID orderId = readyMembershipOrder();
        when(kakaoPayClient.approve(any())).thenReturn(new KakaoPayClient.ApproveResponse(
                "A1", tid, CID, orderId.toString(), userId.toString(), "MONEY",
                new KakaoPayClient.Amount(4_900L), "2026-10-10T12:01:00"));
        OffsetDateTime before = OffsetDateTime.now();

        PaymentResponse approved = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));
        OffsetDateTime expiresAt = membershipExpiry();
        PaymentResponse replay = paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token"));

        assertThat(approved.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(replay.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(expiresAt).isBetween(before.plusDays(30), OffsetDateTime.now().plusDays(30));
        assertThat(membershipExpiry()).isEqualTo(expiresAt);
        assertThat(grantRows(orderId)).isEqualTo(1);
        verify(membershipService, times(1)).grant(userId, orderId);
        verify(kakaoPayClient, times(1)).approve(any());
        assertThat(balance()).isZero();
        assertThat(chargeRows(orderId)).isZero();
    }

    @Test
    void membershipGrantFailureRollsBackPaymentPeriodAndGrantHistoryTogether() {
        UUID orderId = readyMembershipOrder();
        when(kakaoPayClient.approve(any())).thenReturn(new KakaoPayClient.ApproveResponse(
                "A1", tid, CID, orderId.toString(), userId.toString(), "MONEY",
                new KakaoPayClient.Amount(4_900L), "2026-10-10T12:01:00"));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failure after membership grant.");
        }).when(membershipService).grant(userId, orderId);

        assertThatThrownBy(() -> paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        Payment payment = paymentRepository.findById(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVING);
        assertThat(payment.getApprovedAt()).isNull();
        assertThat(payment.getApprovedAid()).isNull();
        assertThat(grantRows(orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_memberships where user_id = ?",
                Long.class, userId)).isZero();
    }

    @Test
    void requestPathAndSchedulerRecoverStaleOrderOnce() throws Exception {
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_10000", UUID.randomUUID())).orderId();
        when(kakaoPayClient.approve(any())).thenThrow(
                new KakaoPayException("approve", KakaoPayException.Kind.UNKNOWN, null, null, null));
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVING);
        jdbcTemplate.update("update payments set status_changed_at = status_changed_at - interval '2 minutes' where id = ?",
                orderId);
        when(kakaoPayClient.order(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new KakaoPayClient.OrderResponse(tid, CID, "SUCCESS_PAYMENT", orderId.toString(), userId.toString(),
                    "MONEY", new KakaoPayClient.Amount(10_000L), null, null, "2026-10-10T12:01:00", null,
                    List.of(new KakaoPayClient.PaymentActionDetail("A1", "2026-10-10T12:01:00", "PAYMENT")));
        });

        List<Outcome<Object>> outcomes = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Object>) () -> {
                    if (index % 2 == 0) {
                        return paymentService.getPayment(userId, orderId);
                    }
                    recoveryScheduler.recoverStalePayments();
                    return "scheduler";
                })
                .toList());

        assertNoFailures(outcomes);
        verify(kakaoPayClient, times(1)).order(any());
        assertThat(paymentStatus(orderId)).isEqualTo("APPROVED");
        assertThat(chargeRows(orderId)).isEqualTo(1);
        assertThat(balance()).isEqualTo(10_000L);
    }

    @Test
    void concurrentCancelsCallKakaoPayOnceAndRevokeOnce() throws Exception {
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", UUID.randomUUID())).orderId();
        when(kakaoPayClient.approve(any())).thenReturn(new KakaoPayClient.ApproveResponse("A1", tid, CID,
                orderId.toString(), userId.toString(), "MONEY", new KakaoPayClient.Amount(5_000L), "2026-10-10T12:01:00"));
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVED);
        when(kakaoPayClient.cancel(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return new KakaoPayClient.CancelResponse("C1", tid, CID, "CANCEL_PAYMENT",
                    new KakaoPayClient.Amount(5_000L), "2026-10-10T12:05:00");
        });

        List<Outcome<PaymentResponse>> outcomes = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<PaymentResponse>) () -> paymentService.cancel(userId, orderId))
                .toList());

        assertNoFailures(outcomes);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.value().status())
                .isIn(PaymentStatus.CANCELING, PaymentStatus.CANCELED));
        verify(kakaoPayClient, times(1)).cancel(any());
        assertThat(paymentStatus(orderId)).isEqualTo("CANCELED");
        Long cancelRows = jdbcTemplate.queryForObject(
                "select count(*) from mileage_transactions where type = 'CHARGE_CANCEL' and reference_id = ?",
                Long.class, orderId);
        assertThat(cancelRows).isEqualTo(1);

        assertThat(balance()).isZero();
    }

    @Test
    void membershipCancelAndNewMembershipReadiesAreSerializedByUserLock() throws Exception {
        UUID orderId = readyMembershipOrder();
        when(kakaoPayClient.approve(any())).thenReturn(new KakaoPayClient.ApproveResponse("A1", tid, CID,
                orderId.toString(), userId.toString(), "MONEY", new KakaoPayClient.Amount(4_900L), "2026-10-10T12:01:00"));
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVED);
        OffsetDateTime expiresAt = membershipExpiry();
        when(kakaoPayClient.ready(any())).thenAnswer(invocation -> {
            String readyTid = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
            return new KakaoPayClient.ReadyResponse(readyTid, "https://pc", "https://mobile", "2026-10-10T12:00:00");
        });
        // 외부 취소를 늦춰 동시 ready가 모두 CANCELING 구간 안에서 끝나게 한다.
        when(kakaoPayClient.cancel(any())).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return new KakaoPayClient.CancelResponse("C1", tid, CID, "CANCEL_PAYMENT",
                    new KakaoPayClient.Amount(4_900L), "2026-10-10T12:05:00");
        });

        List<Outcome<Object>> outcomes = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Object>) () -> index == 0
                        ? paymentService.cancel(userId, orderId)
                        : paymentService.ready(userId, new PaymentReadyRequest("MEMBERSHIP_30D", UUID.randomUUID())))
                .toList());

        Outcome<Object> cancel = outcomes.get(0);
        List<Outcome<Object>> readies = outcomes.subList(1, outcomes.size());
        Long newOrders = jdbcTemplate.queryForObject(
                "select count(*) from payments where buyer_id = ? and product_code = 'MEMBERSHIP_30D' and id <> ?",
                Long.class, userId, orderId);
        if (cancel.error() == null) {
            // 취소가 먼저 잠금을 잡았다: 취소 중의 이용권 ready는 모두 409이고 기간은 한 번만 회수된다.
            assertThat(((PaymentResponse) cancel.value()).status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(readies).allSatisfy(outcome -> assertConflict(outcome.error()));
            assertThat(newOrders).isZero();
            assertThat(grantStatus(orderId)).isEqualTo("REVOKED");
            assertThat(membershipExpiry()).isBefore(expiresAt);
            verify(kakaoPayClient, times(1)).cancel(any());
        } else {
            // ready가 먼저 주문을 만들었다: 미완료 이용권 주문이 있으므로 취소는 409이고 기간은 그대로다.
            assertConflict(cancel.error());
            assertNoFailures(readies);
            assertThat(newOrders).isEqualTo(CONCURRENT_REQUESTS - 1);
            assertThat(paymentStatus(orderId)).isEqualTo("APPROVED");
            assertThat(grantStatus(orderId)).isEqualTo("GRANTED");
            assertThat(membershipExpiry()).isEqualTo(expiresAt);
            verify(kakaoPayClient, times(0)).cancel(any());
        }
    }

    @Test
    void membershipReadiesDuringMembershipCancelAreRejected() throws Exception {
        UUID orderId = readyMembershipOrder();
        when(kakaoPayClient.approve(any())).thenReturn(new KakaoPayClient.ApproveResponse("A1", tid, CID,
                orderId.toString(), userId.toString(), "MONEY", new KakaoPayClient.Amount(4_900L), "2026-10-10T12:01:00"));
        assertThat(paymentService.approve(userId, new PaymentApproveRequest(orderId, "pg-token")).status())
                .isEqualTo(PaymentStatus.APPROVED);
        when(kakaoPayClient.ready(any())).thenAnswer(invocation -> {
            String readyTid = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
            return new KakaoPayClient.ReadyResponse(readyTid, "https://pc", "https://mobile", "2026-10-10T12:00:00");
        });
        CountDownLatch releaseCancel = new CountDownLatch(1);
        when(kakaoPayClient.cancel(any())).thenAnswer(invocation -> {
            assertThat(releaseCancel.await(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return new KakaoPayClient.CancelResponse("C1", tid, CID, "CANCEL_PAYMENT",
                    new KakaoPayClient.Amount(4_900L), "2026-10-10T12:05:00");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentResponse> cancel = executor.submit(() -> paymentService.cancel(userId, orderId));
            // 회수 선반영이 커밋돼 CANCELING이 보일 때까지 기다린다(외부 취소는 아직 대기 중).
            long deadline = System.nanoTime() + RESULT_TIMEOUT.toNanos();
            while (!"CANCELING".equals(paymentStatus(orderId))) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(20);
            }

            List<Outcome<PaymentReadyResponse>> readies = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(ignored -> (Supplier<PaymentReadyResponse>) () ->
                            paymentService.ready(userId, new PaymentReadyRequest("MEMBERSHIP_30D", UUID.randomUUID())))
                    .toList());
            releaseCancel.countDown();

            assertThat(readies).allSatisfy(outcome -> assertConflict(outcome.error()));
            assertThat(cancel.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).status())
                    .isEqualTo(PaymentStatus.CANCELED);
        } finally {
            releaseCancel.countDown();
            executor.shutdownNow();
        }
        Long newOrders = jdbcTemplate.queryForObject(
                "select count(*) from payments where buyer_id = ? and product_code = 'MEMBERSHIP_30D' and id <> ?",
                Long.class, userId, orderId);
        assertThat(newOrders).isZero();
        assertThat(grantStatus(orderId)).isEqualTo("REVOKED");
        assertThat(membershipExpiry()).isBeforeOrEqualTo(OffsetDateTime.now());
        // 취소가 끝나면 다시 이용권을 결제할 수 있다.
        assertThat(readyMembershipOrder()).isNotNull();
    }

    @Test
    void concurrentReadiesWithSameRequestIdCreateOneOrder() throws Exception {
        UUID requestId = UUID.randomUUID();

        List<Outcome<PaymentReadyResponse>> outcomes = runConcurrently(IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<PaymentReadyResponse>) () ->
                        paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", requestId)))
                .toList());

        assertNoFailures(outcomes);
        assertThat(outcomes).extracting(outcome -> outcome.value().orderId()).containsOnly(outcomes.get(0).value().orderId());
        verify(kakaoPayClient, times(1)).ready(any());
        Long orders = jdbcTemplate.queryForObject(
                "select count(*) from payments where buyer_id = ? and request_id = ?", Long.class, userId, requestId);
        assertThat(orders).isEqualTo(1);
    }

    @Test
    void ledgerAllowsOneChargeAndOneChargeCancelPerPayment() {
        UUID paymentId = UUID.randomUUID();
        insertLedger("CHARGE", paymentId);
        insertLedger("CHARGE_CANCEL", paymentId);
        // 다른 거래 종류의 referenceId와는 충돌하지 않는다.
        insertLedger("MENTORING_PAY", paymentId);
        insertLedger("MENTORING_PAY", paymentId);

        assertThatThrownBy(() -> insertLedger("CHARGE", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger("CHARGE_CANCEL", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void paymentStatusCheckRejectsUnknownStatus() {
        UUID orderId = paymentService.ready(userId, new PaymentReadyRequest("CHARGE_5000", UUID.randomUUID())).orderId();

        assertThatThrownBy(() -> jdbcTemplate.update("update payments set status = 'PAID' where id = ?", orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertLedger(String type, UUID referenceId) {
        jdbcTemplate.update("""
                insert into mileage_transactions (id, user_id, amount, balance_after, type, reference_id, created_at)
                values (?, ?, 0, 0, ?, ?, ?)
                """, UUID.randomUUID(), userId, type, referenceId, OffsetDateTime.now());
    }

    private UUID readyMembershipOrder() {
        return paymentService.ready(userId, new PaymentReadyRequest("MEMBERSHIP_30D", UUID.randomUUID())).orderId();
    }

    private void assertConflict(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private String grantStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject("select status from membership_grants where payment_id = ?",
                String.class, paymentId);
    }

    private OffsetDateTime membershipExpiry() {
        return jdbcTemplate.queryForObject("select expires_at from user_memberships where user_id = ?",
                OffsetDateTime.class, userId);
    }

    private long grantRows(UUID paymentId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from membership_grants where payment_id = ?",
                Long.class, paymentId);
        return count == null ? 0L : count;
    }

    private String paymentStatus(UUID orderId) {
        return jdbcTemplate.queryForObject("select status from payments where id = ?", String.class, orderId);
    }

    private long chargeRows(UUID orderId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from mileage_transactions where type = 'CHARGE' and reference_id = ?",
                Long.class, orderId);
        return count == null ? 0L : count;
    }

    private long balance() {
        Long balance = jdbcTemplate.query("select balance from mileage_wallets where user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return balance == null ? 0L : balance;
    }

    private UUID createUser() {
        return transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            User user = User.createLocal("pay+" + suffix + "@example.com", "pay" + suffix, "pay", "encoded-password");
            user.setProfile(UserProfile.createDefault(user));
            return userRepository.saveAndFlush(user).getId();
        });
    }

    // 서비스가 트랜잭션 경계를 직접 관리하므로 테스트 트랜잭션으로 감싸지 않는다.
    private <T> List<Outcome<T>> runConcurrently(List<Supplier<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "payment-postgres-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Supplier<T> operation : operations) {
                futures.add(executor.submit(concurrentTask(operation, ready, start)));
            }
            assertThat(ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private <T> Callable<Outcome<T>> concurrentTask(Supplier<T> operation, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            try {
                if (!start.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return Outcome.failure(new IllegalStateException("Concurrent start timed out."));
                }
                return Outcome.success(operation.get());
            } catch (Throwable error) {
                return Outcome.failure(error);
            }
        };
    }

    private void assertNoFailures(List<? extends Outcome<?>> outcomes) {
        for (Outcome<?> outcome : outcomes) {
            assertThat(outcome.error())
                    .as("Concurrent payment operation must complete without an exception")
                    .isNull();
        }
    }

    private record Outcome<T>(T value, Throwable error) {
        private static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failure(Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("PAYMENT_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("PAYMENT_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("PAYMENT_POSTGRES_TEST_PASSWORD", true);
            String schema = "payment_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL concurrency test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL test schema.", error);
            }
        }
    }
}
