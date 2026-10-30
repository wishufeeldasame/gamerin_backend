package com.gamerin.backend.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.auth.service.EmailVerificationMailService;
import com.gamerin.backend.domain.auth.service.EmailVerificationService;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(15);
    private static PostgresSchema postgresSchema;

    @Autowired
    private EmailVerificationService emailVerificationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmailVerificationMailService emailVerificationMailService;

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
        registry.add("app.moderation.enabled", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    /**
     * #100: 발송 이력이 없는 이메일로 동시에 최초 요청이 들어와도 정확히 1건만 발송하고 나머지는 429로 거절한다.
     */
    @Test
    void concurrentFirstSendRequestsAllowOnlyOneSend() throws Exception {
        String email = "first-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@example.com";

        List<Throwable> errors = runConcurrently(() -> emailVerificationService.sendSignupCode(email));

        long successCount = errors.stream().filter(error -> error == null).count();
        long tooManyRequestsCount = errors.stream()
                .filter(ResponseStatusException.class::isInstance)
                .map(ResponseStatusException.class::cast)
                .filter(ex -> ex.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value())
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(tooManyRequestsCount).isEqualTo(CONCURRENT_REQUESTS - 1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from email_verifications where email = ?", Long.class, email)).isEqualTo(1L);
        verify(emailVerificationMailService, times(1)).sendSignupVerificationCode(eq(email), anyString());
    }

    private List<Throwable> runConcurrently(Runnable operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                            return new IllegalStateException("Concurrent start timed out.");
                        }
                        operation.run();
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                }));
            }
            assertThat(ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();

            List<Throwable> errors = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                errors.add(future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return errors;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("HASHTAG_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("HASHTAG_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("HASHTAG_POSTGRES_TEST_PASSWORD", true);
            String schema = "email_verification_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL email verification test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL email verification test schema.", error);
            }
        }
    }
}
