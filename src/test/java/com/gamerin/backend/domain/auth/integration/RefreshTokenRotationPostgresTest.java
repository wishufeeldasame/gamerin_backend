package com.gamerin.backend.domain.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.auth.service.LocalAuthService;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

/**
 * refresh 토큰 행 잠금(select ... for update)과 회전·재사용 탐지를 실제 PostgreSQL 동시 트랜잭션으로 검증한다.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "REFRESH_TOKEN_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefreshTokenRotationPostgresTest {

    private static final int CONCURRENT_REQUESTS = 4;

    private static PostgresSchema postgresSchema;

    @Autowired
    private LocalAuthService localAuthService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void concurrentRefreshWithSameTokenRotatesOnlyOnce() throws Exception {
        User user = saveUser();
        String rawToken = tokenService.issueTokens(user).refreshToken();

        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        localAuthService.refresh(rawToken);
                        return HttpStatus.OK.value();
                    } catch (ResponseStatusException error) {
                        return error.getStatusCode().value();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
            assertThat(statuses).filteredOn(status -> status == HttpStatus.OK.value()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        // 원래 토큰은 회전으로 폐기되고, 새 토큰은 한 개만 발급된다.
        assertThat(count("select count(*) from refresh_tokens where token_hash = ? and rotated_at is not null",
                tokenService.sha256(rawToken))).isEqualTo(1);
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", user.getId()))
                .isEqualTo(1);
    }

    @Test
    void reusingRotatedTokenAfterGraceRevokesAllUserTokens() {
        User user = saveUser();
        String rawToken = tokenService.issueTokens(user).refreshToken();
        localAuthService.refresh(rawToken);
        tokenService.issueTokens(user); // 다른 기기의 세션
        jdbcTemplate.update("update refresh_tokens set rotated_at = now() - interval '10 minutes' where token_hash = ?",
                tokenService.sha256(rawToken));
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", user.getId()))
                .isEqualTo(2);

        assertThatThrownBy(() -> localAuthService.refresh(rawToken))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        // 401 이어도 롤백하지 않고 전체 폐기를 커밋한다.
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", user.getId()))
                .isZero();
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private User saveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal("refresh+" + suffix + "@example.com", "refresh" + suffix, "refresh", "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("REFRESH_TOKEN_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("REFRESH_TOKEN_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("REFRESH_TOKEN_POSTGRES_TEST_PASSWORD", true);
            String schema = "refresh_token_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL refresh token test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL refresh token test schema.", error);
            }
        }
    }
}
