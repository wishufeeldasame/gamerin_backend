package com.gamerin.backend.domain.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.AccountWithdrawalService;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "ACCOUNT_WITHDRAWAL_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountWithdrawalPostgresTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private AccountWithdrawalService accountWithdrawalService;
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
    void withdrawAnonymizesUserAndCleansUpRelatedData() {
        User leaver = saveUser("leaver");
        User other = saveUser("other");
        UUID leaverId = leaver.getId();
        String originalEmail = leaver.getEmail();
        String originalHandle = leaver.getHandle();

        UUID leaverPostId = insertPost(leaverId);
        UUID otherPostId = insertPost(other.getId());
        jdbcTemplate.update("insert into post_comments (post_id, author_id, content) values (?, ?, 'hi')",
                otherPostId, leaverId);
        jdbcTemplate.update("insert into post_comments (post_id, author_id, content) values (?, ?, 'mine')",
                otherPostId, other.getId());
        jdbcTemplate.update("update posts set comment_count = 2 where id = ?", otherPostId);
        jdbcTemplate.update("insert into post_likes (post_id, user_id) values (?, ?)", otherPostId, leaverId);
        jdbcTemplate.update("update posts set like_count = 1 where id = ?", otherPostId);
        UUID followId = jdbcTemplate.queryForObject(
                "insert into follows (follower_id, followee_id) values (?, ?) returning id",
                UUID.class, leaverId, other.getId());
        jdbcTemplate.update("insert into follows (follower_id, followee_id) values (?, ?)", other.getId(), leaverId);
        jdbcTemplate.update("insert into notifications (recipient_id, actor_id, type, follow_id) values (?, ?, 'FOLLOW', ?)",
                other.getId(), leaverId, followId);
        jdbcTemplate.update("insert into post_bookmarks (post_id, user_id) values (?, ?)", otherPostId, leaverId);
        jdbcTemplate.update("""
                insert into social_accounts (user_id, provider, provider_user_id) values (?, 'GOOGLE', ?)
                """, leaverId, "google-" + leaverId);
        jdbcTemplate.update("insert into mentor_profiles (user_id, about) values (?, 'contact me')", leaverId);
        jdbcTemplate.update("""
                insert into refresh_tokens (user_id, token_hash, expires_at) values (?, ?, now() + interval '1 day')
                """, leaverId, "hash-" + leaverId);

        accountWithdrawalService.withdraw(leaverId);

        User withdrawn = userRepository.findById(leaverId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(withdrawn.getDeletedAt()).isNotNull();
        assertThat(withdrawn.getEmail()).isNull();
        assertThat(withdrawn.getPasswordHash()).isNull();
        assertThat(withdrawn.getHandle()).startsWith("deleted_");
        assertThat(withdrawn.getNickname()).isEqualTo(User.WITHDRAWN_NICKNAME);

        assertThat(count("select count(*) from posts where id = ? and deleted_at is not null", leaverPostId)).isEqualTo(1);
        assertThat(count("select count(*) from post_comments where author_id = ?", leaverId)).isZero();
        assertThat(count("select comment_count from posts where id = ?", otherPostId)).isEqualTo(1);
        assertThat(count("select like_count from posts where id = ?", otherPostId)).isEqualTo(1);
        assertThat(count("select count(*) from follows where follower_id = ? or followee_id = ?", leaverId, leaverId)).isZero();
        assertThat(count("select count(*) from notifications where actor_id = ? or recipient_id = ?", leaverId, leaverId)).isZero();
        assertThat(count("select count(*) from post_bookmarks where user_id = ?", leaverId)).isZero();
        assertThat(count("select count(*) from social_accounts where user_id = ?", leaverId)).isZero();
        assertThat(count("select count(*) from mentor_profiles where user_id = ? and status = 'INACTIVE' and about is null",
                leaverId)).isEqualTo(1);
        assertThat(count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", leaverId)).isZero();

        // 같은 email/handle로 재가입할 수 있어야 한다
        User rejoined = User.createLocal(originalEmail, originalHandle, "rejoined", "encoded-password");
        rejoined.setProfile(UserProfile.createDefault(rejoined));
        assertThat(userRepository.saveAndFlush(rejoined).getId()).isNotEqualTo(leaverId);
    }

    @Test
    void withdrawIsBlockedWhileMentoringEscrowIsHeld() {
        User mentor = saveUser("mentor");
        User mentee = saveUser("mentee");
        jdbcTemplate.update("insert into mentor_profiles (user_id) values (?)", mentor.getId());
        UUID programId = jdbcTemplate.queryForObject("""
                insert into mentoring_programs (mentor_id, game_name, title, price) values (?, 'LOL', 'coaching', 100)
                returning id
                """, UUID.class, mentor.getId());
        jdbcTemplate.update("""
                insert into mentoring_applications (program_id, mentee_id, applied_mileage, status, payment_status)
                values (?, ?, 100, 'ACCEPTED', 'ESCROW_HELD')
                """, programId, mentee.getId());

        for (User user : new User[] {mentor, mentee}) {
            assertThatThrownBy(() -> accountWithdrawalService.withdraw(user.getId()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        }
        // 실패한 탈퇴 시도는 프로그램 소프트 삭제까지 롤백한다
        assertThat(count("select count(*) from mentoring_programs where id = ? and deleted_at is null", programId))
                .isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private UUID insertPost(UUID authorId) {
        return jdbcTemplate.queryForObject("insert into posts (author_id, content) values (?, 'post') returning id",
                UUID.class, authorId);
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(prefix + "+" + suffix + "@example.com", prefix + suffix, prefix, "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("ACCOUNT_WITHDRAWAL_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("ACCOUNT_WITHDRAWAL_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("ACCOUNT_WITHDRAWAL_POSTGRES_TEST_PASSWORD", true);
            String schema = "account_withdrawal_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL account withdrawal test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL account withdrawal test schema.", error);
            }
        }
    }
}
