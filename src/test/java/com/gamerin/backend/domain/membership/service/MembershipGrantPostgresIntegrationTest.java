package com.gamerin.backend.domain.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.dto.response.MyMembershipResponse;
import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.repository.MembershipGrantRepository;
import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.payment.entity.Payment;
import com.gamerin.backend.domain.payment.entity.PaymentStatus;
import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "PAYMENT_POSTGRES_TEST_URL", matches = ".+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@Import(MembershipGrantPostgresIntegrationTest.FixedClockConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MembershipGrantPostgresIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T00:00:00.123456Z");
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(20);
    private static PostgresSchema postgresSchema;

    @Autowired
    private MembershipService membershipService;
    @Autowired
    private UserMembershipRepository membershipRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Flyway flyway;

    private TransactionTemplate transactionTemplate;
    private UUID userId;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        postgresSchema = PostgresSchema.create();
        registry.add("spring.datasource.url", postgresSchema::url);
        registry.add("spring.datasource.username", postgresSchema::username);
        registry.add("spring.datasource.password", postgresSchema::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", postgresSchema::schema);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "set lock_timeout = '10s'");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", postgresSchema::schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", postgresSchema::schema);
        registry.add("spring.flyway.default-schema", postgresSchema::schema);
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() throws SQLException {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        userId = transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            User user = User.createLocal("grant+" + suffix + "@example.com", "grant" + suffix,
                    "Member", "encoded-password");
            user.setProfile(UserProfile.createDefault(user));
            return userRepository.saveAndFlush(user).getId();
        });
        assertThat(AopUtils.isAopProxy(membershipService)).isTrue();
    }

    @Test
    void concurrentFirstPurchasesCreateOneMembershipAndTwoGrantsForSixtyDays() throws Exception {
        UUID first = approvedPayment();
        UUID second = approvedPayment();

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> membershipService.grant(userId, first),
                () -> membershipService.grant(userId, second)));

        assertNoFailures(outcomes);
        assertThat(membershipRows()).isEqualTo(1);
        assertThat(grantRows()).isEqualTo(2);
        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(60));
        assertThat(jdbcTemplate.queryForList("""
                select granted_from from membership_grants where user_id = ? order by created_at
                """, OffsetDateTime.class, userId)).containsExactly(NOW, NOW.plusDays(30));
    }

    @Test
    void concurrentReplaysOfOnePaymentGrantThirtyDaysExactlyOnce() throws Exception {
        UUID paymentId = approvedPayment();

        assertNoFailures(runConcurrently(List.of(
                () -> membershipService.grant(userId, paymentId),
                () -> membershipService.grant(userId, paymentId))));

        assertThat(grantRows()).isEqualTo(1);
        assertThat(membershipRows()).isEqualTo(1);
        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
    }

    @ParameterizedTest
    @ValueSource(ints = {-30, 0, 5})
    void purchaseExtendsFromLaterOfCurrentTimeAndExistingExpiry(int expiryDays) {
        saveMembership(NOW.plusDays(expiryDays));
        UUID paymentId = approvedPayment();

        membershipService.grant(userId, paymentId);

        OffsetDateTime expectedFrom = NOW.plusDays(Math.max(0, expiryDays));
        assertThat(grantedFrom(paymentId)).isEqualTo(expectedFrom);
        assertThat(membershipExpiry()).isEqualTo(expectedFrom.plusDays(30));
    }

    @Test
    void sameClockPurchasesHaveStrictOrderAndEarlierPurchaseCannotBeRevoked() {
        UUID first = approvedPayment();
        UUID second = approvedPayment();
        membershipService.grant(userId, first);
        membershipService.grant(userId, second);

        assertThat(createdAt(second)).isAfter(createdAt(first));
        assertConflict(() -> membershipService.revoke(first));

        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(60));
        assertThat(grantStatus(first)).isEqualTo("GRANTED");
        assertThat(grantStatus(second)).isEqualTo("GRANTED");
    }

    @Test
    void revokingLatestPurchasePreservesPreviousPeriodAndStillBlocksEarlierPurchase() {
        UUID first = approvedPayment();
        UUID second = approvedPayment();
        membershipService.grant(userId, first);
        membershipService.grant(userId, second);

        membershipService.revoke(second);

        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
        assertThat(grantStatus(first)).isEqualTo("GRANTED");
        assertThat(grantStatus(second)).isEqualTo("REVOKED");
        assertThat(grantRows()).isEqualTo(2);
        assertConflict(() -> membershipService.revoke(first));
        assertConflict(() -> membershipService.grant(userId, second));
        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
        assertThat(grantRows()).isEqualTo(2);
    }

    @Test
    void revokingPurchaseRetainsPeriodThatPredatedThePurchase() {
        OffsetDateTime previousExpiry = NOW.plusDays(5);
        saveMembership(previousExpiry);
        UUID paymentId = approvedPayment();
        membershipService.grant(userId, paymentId);

        membershipService.revoke(paymentId);

        assertThat(membershipExpiry()).isEqualTo(previousExpiry);
        assertThat(grantStatus(paymentId)).isEqualTo("REVOKED");
    }

    @Test
    void concurrentRevokeCallsRevokeOnceAndReturnConflictForReplay() throws Exception {
        UUID paymentId = approvedPayment();
        membershipService.grant(userId, paymentId);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> membershipService.revoke(paymentId),
                () -> membershipService.revoke(paymentId)));

        assertThat(outcomes.stream().filter(outcome -> outcome.error() == null)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.error() != null)).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)));
        assertThat(membershipExpiry()).isEqualTo(NOW);
        assertThat(grantStatus(paymentId)).isEqualTo("REVOKED");
        assertThat(grantRows()).isEqualTo(1);
    }

    @Test
    void purchaseRacingWithRevokePreservesTheResultOfEitherSerialOrder() throws Exception {
        UUID first = approvedPayment();
        UUID second = approvedPayment();
        membershipService.grant(userId, first);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> membershipService.revoke(first),
                () -> membershipService.grant(userId, second)));

        assertThat(outcomes.get(1).error()).isNull();
        assertThat(grantStatus(second)).isEqualTo("GRANTED");
        assertThat(grantRows()).isEqualTo(2);
        if (outcomes.get(0).error() == null) {
            assertThat(grantStatus(first)).isEqualTo("REVOKED");
            assertThat(grantedFrom(second)).isEqualTo(NOW);
            assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
        } else {
            assertThat(outcomes.get(0).error()).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(grantStatus(first)).isEqualTo("GRANTED");
            assertThat(grantedFrom(second)).isEqualTo(NOW.plusDays(30));
            assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(60));
        }
        assertThat(createdAt(second)).isAfter(createdAt(first));
    }

    @Test
    void callerFailureRollsBackNewMembershipAndGrant() {
        UUID paymentId = approvedPayment();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            membershipService.grant(userId, paymentId);
            throw new CallerFailure();
        })).isInstanceOf(CallerFailure.class);

        assertThat(membershipRows()).isZero();
        assertThat(grantRows()).isZero();
        assertThat(paymentStatus(paymentId)).isEqualTo("APPROVED");
    }

    @Test
    void callerFailureRollsBackSuccessfulRevoke() {
        UUID paymentId = approvedPayment();
        membershipService.grant(userId, paymentId);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            membershipService.revoke(paymentId);
            throw new CallerFailure();
        })).isInstanceOf(CallerFailure.class);

        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
        assertThat(grantStatus(paymentId)).isEqualTo("GRANTED");
    }

    @Test
    void rejectedRevokeRollsBackCallersPaymentTransitionAndKeepsBothPeriods() {
        UUID first = approvedPayment();
        UUID second = approvedPayment();
        membershipService.grant(userId, first);
        membershipService.grant(userId, second);

        assertConflict(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("update payments set status = 'CANCELING' where id = ?", first);
            membershipService.revoke(first);
        }));

        assertThat(paymentStatus(first)).isEqualTo("APPROVED");
        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(60));
        assertThat(grantStatus(first)).isEqualTo("GRANTED");
        assertThat(grantStatus(second)).isEqualTo("GRANTED");
    }

    @Test
    void missingMembershipReadDoesNotCreateMembershipOrGrant() {
        MyMembershipResponse response = membershipService.getMyMembership(userId);

        assertThat(response.active()).isFalse();
        assertThat(response.expiresAt()).isNull();
        assertThat(response.price()).isEqualTo(PaymentProduct.MEMBERSHIP_30D.getPrice());
        assertThat(response.durationDays()).isEqualTo(PaymentProduct.MEMBERSHIP_30D.getDurationDays());
        assertThat(membershipRows()).isZero();
        assertThat(grantRows()).isZero();
    }

    @Test
    void expiredMembershipReadPreservesStoredPeriodWithoutWritingGrant() {
        saveMembership(NOW);
        OffsetDateTime updatedAt = jdbcTemplate.queryForObject(
                "select updated_at from user_memberships where user_id = ?", OffsetDateTime.class, userId);

        MyMembershipResponse response = membershipService.getMyMembership(userId);

        assertThat(response.active()).isFalse();
        assertThat(response.expiresAt()).isEqualTo(NOW);
        assertThat(membershipRows()).isEqualTo(1);
        assertThat(grantRows()).isZero();
        assertThat(jdbcTemplate.queryForObject("select updated_at from user_memberships where user_id = ?",
                OffsetDateTime.class, userId)).isEqualTo(updatedAt);
    }

    @Test
    void flywayMigratesToLatestAndCanValidateAndRunAgainWithoutChanges() {
        // 새 migration이 추가될 때마다 깨지지 않도록 특정 버전 대신 대기 migration이 없는지만 본다.
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void grantSchemaRejectsDuplicatePaymentBrokenReferencesInvalidStatusAndNonpositivePeriod() {
        UUID paymentId = approvedPayment();
        UUID otherPaymentId = approvedPayment();
        membershipService.grant(userId, paymentId);

        assertThatThrownBy(() -> insertGrant(userId, paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertGrant(userId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertGrant(UUID.randomUUID(), otherPaymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update membership_grants set status = 'REFUNDED' where payment_id = ?", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update membership_grants set granted_until = granted_from where payment_id = ?", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(grantRows()).isEqualTo(1);
        assertThat(grantStatus(paymentId)).isEqualTo("GRANTED");
        assertThat(membershipExpiry()).isEqualTo(NOW.plusDays(30));
    }

    private void insertGrant(UUID grantUserId, UUID paymentId) {
        jdbcTemplate.update("""
                insert into membership_grants
                    (id, user_id, payment_id, granted_from, granted_until, status, created_at)
                values (?, ?, ?, ?, ?, 'GRANTED', ?)
                """, UUID.randomUUID(), grantUserId, paymentId, NOW, NOW.plusDays(30), NOW);
    }

    private UUID approvedPayment() {
        return transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.saveAndFlush(Payment.create(
                    userRepository.getReferenceById(userId), UUID.randomUUID(), PaymentProduct.MEMBERSHIP_30D,
                    "TC0ONETIME", NOW));
            assertThat(paymentRepository.transition(payment.getId(), PaymentStatus.CREATED, NOW,
                    PaymentStatus.APPROVED, NOW)).isEqualTo(1);
            return payment.getId();
        });
    }

    private void saveMembership(OffsetDateTime expiry) {
        transactionTemplate.executeWithoutResult(status -> membershipRepository.saveAndFlush(
                UserMembership.create(userRepository.getReferenceById(userId), expiry)));
    }

    private long membershipRows() {
        return jdbcTemplate.queryForObject("select count(*) from user_memberships where user_id = ?",
                Long.class, userId);
    }

    private long grantRows() {
        return jdbcTemplate.queryForObject("select count(*) from membership_grants where user_id = ?",
                Long.class, userId);
    }

    private OffsetDateTime membershipExpiry() {
        return jdbcTemplate.queryForObject("select expires_at from user_memberships where user_id = ?",
                OffsetDateTime.class, userId);
    }

    private OffsetDateTime grantedFrom(UUID paymentId) {
        return jdbcTemplate.queryForObject("select granted_from from membership_grants where payment_id = ?",
                OffsetDateTime.class, paymentId);
    }

    private OffsetDateTime createdAt(UUID paymentId) {
        return jdbcTemplate.queryForObject("select created_at from membership_grants where payment_id = ?",
                OffsetDateTime.class, paymentId);
    }

    private String grantStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject("select status from membership_grants where payment_id = ?",
                String.class, paymentId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject("select status from payments where id = ?", String.class, paymentId);
    }

    private void assertConflict(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    private List<Outcome> runConcurrently(List<Runnable> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "membership-grant-postgres-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Runnable operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                            return new Outcome(new IllegalStateException("Concurrent start timed out."));
                        }
                        operation.run();
                        return new Outcome(null);
                    } catch (Throwable error) {
                        return new Outcome(error);
                    }
                }));
            }
            assertThat(ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void assertNoFailures(List<Outcome> outcomes) {
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error())
                .as("Concurrent membership operation must complete without an exception").isNull());
    }

    private record Outcome(Throwable error) {
    }

    private static class CallerFailure extends RuntimeException {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        MembershipService membershipService(UserMembershipRepository memberships, MembershipGrantRepository grants,
                PaymentRepository payments, UserRepository users) {
            return new MembershipService(memberships, grants, payments, users,
                    Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        private static PostgresSchema create() {
            PostgresSchema fixture = new PostgresSchema(environment("PAYMENT_POSTGRES_TEST_URL", false),
                    environment("PAYMENT_POSTGRES_TEST_USERNAME", false),
                    environment("PAYMENT_POSTGRES_TEST_PASSWORD", true),
                    "membership_grant_" + UUID.randomUUID().toString().replace("-", ""));
            try (Connection connection = DriverManager.getConnection(fixture.url, fixture.username, fixture.password);
                    var statement = connection.createStatement()) {
                statement.execute("create schema " + fixture.schema);
                return fixture;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not prepare the membership grant test schema.", failure);
            }
        }

        private static String environment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL membership grant test.");
            }
            return value;
        }

        private void close() throws SQLException {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
