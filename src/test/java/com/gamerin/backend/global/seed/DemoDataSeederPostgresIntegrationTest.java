package com.gamerin.backend.global.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gamerin.backend.domain.notification.entity.NotificationType;

/**
 * MessageService 의 대화 생성이 PostgreSQL 전용 SQL(on conflict)을 사용하므로 H2 가 아닌 임시 PostgreSQL 스키마에서 검증한다.
 * 앱 기동 시 seed 프로필 + app.seed.enabled=true 로 시더가 실행된 결과를 검사한다.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "SEED_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "app.seed.enabled=true")
@ActiveProfiles({"local", "seed"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoDataSeederPostgresIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

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
        // 시드 문구는 외부 검열 API 를 거치지 않아야 하므로 검열을 켠 상태(키 없음)로 검증한다.
        registry.add("openai.moderation.enabled", () -> "true");
        registry.add("openai.api.key", () -> "");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    void startupSeedCreatesConsistentDemoData() {
        String passwordHash = jdbcTemplate.queryForObject("select password_hash from users where handle = 'demo01'", String.class);
        assertThat(passwordEncoder.matches(DemoDataSeeder.PASSWORD, passwordHash)).isTrue();
        assertThat(count("select count(*) from users")).isEqualTo(20);
        assertThat(count("select count(distinct email) from users where email like '%.test'")).isEqualTo(20);
        assertThat(count("select count(*) from users u join user_profiles p on p.user_id = u.id"
                + " where p.bio is not null and p.game_stats -> 'PUBG' is not null and p.game_stats -> 'R6' is not null and p.game_stats -> 'LOL' is not null")).isEqualTo(20);
        assertThat(count("select count(*) from posts")).isEqualTo(100);

        assertEveryUserHas("posts", "author_id", 5);
        assertEveryUserHas("follows", "follower_id", 5);
        assertEveryUserHas("post_likes", "user_id", 10);
        assertEveryUserHas("post_comments", "author_id", 3);
        assertEveryUserHas("post_reposts", "user_id", 3);
        assertEveryUserHas("post_shares", "user_id", 2);
        assertEveryUserHas("bookmark_collections", "user_id", 2);
        assertEveryUserHas("post_bookmarks", "user_id", 8);
        assertThat(count("select count(*) from post_hashtags")).isEqualTo(200);
        assertThat(count("select count(*) from user_mentions where post_id is not null")).isEqualTo(100);
        assertThat(count("select count(*) from user_mentions where comment_id is not null")).isEqualTo(60);

        // 게시물 집계 카운터와 실제 관계 행 수
        assertThat(count("select count(*) from posts p where"
                + " p.like_count <> (select count(*) from post_likes l where l.post_id = p.id)"
                + " or p.comment_count <> (select count(*) from post_comments c where c.post_id = p.id)"
                + " or p.share_count <> (select count(*) from post_shares s where s.post_id = p.id)")).isZero();

        // 북마크 컬렉션 항목은 같은 사용자의 북마크만 참조
        assertThat(count("select count(*) from bookmark_collection_items")).isEqualTo(120);
        assertThat(count("select count(*) from bookmark_collection_items i"
                + " join bookmark_collections c on c.id = i.collection_id"
                + " join post_bookmarks b on b.id = i.post_bookmark_id"
                + " where c.user_id <> b.user_id")).isZero();

        // DM
        assertThat(count("select count(*) from message_conversations")).isEqualTo(40);
        assertThat(count("select count(*) from direct_messages")).isEqualTo(160);
        assertThat(count("select count(*) from direct_messages where shared_post_id is not null")).isEqualTo(40);
        assertThat(count("select count(distinct sender_id) from direct_messages")).isEqualTo(20);
        assertThat(count("select count(*) from notifications where type = 'DIRECT_MESSAGE'")).isEqualTo(80);
        assertThat(count("select count(*) from notifications where type = 'DIRECT_MESSAGE' and read_at is null")).isEqualTo(20);

        // 멘토링
        assertThat(count("select count(*) from mentor_profiles")).isEqualTo(5);
        assertThat(count("select count(*) from mentoring_programs")).isEqualTo(10);
        assertThat(count("select count(distinct mentee_id) from mentoring_applications")).isEqualTo(20);
        assertThat(strings("select distinct status from mentoring_applications"))
                .containsExactlyInAnyOrder("APPLIED", "ACCEPTED", "REJECTED", "ONGOING", "FINISHED", "COMPLETED", "CANCELLED");
        assertThat(strings("select distinct payment_status from mentoring_applications"))
                .containsExactlyInAnyOrder("ESCROW_HELD", "REFUNDED", "SETTLED");
        long completed = count("select count(*) from mentoring_applications where status = 'COMPLETED'");
        long refunded = count("select count(*) from mentoring_applications where payment_status = 'REFUNDED'");
        assertThat(completed).isPositive();
        assertThat(count("select count(*) from mentoring_reviews")).isEqualTo(completed);

        assertThat(strings("select distinct type from notifications"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(NotificationType.values()).map(Enum::name).toList());

        // 마일리지 원장
        Map<String, Long> ledger = jdbcTemplate.queryForList("select type, count(*) as cnt from mileage_transactions group by type")
                .stream()
                .collect(Collectors.toMap(row -> (String) row.get("type"), row -> ((Number) row.get("cnt")).longValue()));
        assertThat(ledger).containsOnly(
                Map.entry("CHARGE", 20L),
                Map.entry("MENTORING_PAY", 20L),
                Map.entry("MENTORING_REFUND", refunded),
                Map.entry("SETTLEMENT", completed)
        );
        assertThat(count("select count(*) from mileage_wallets where balance < 0")).isZero();
        assertThat(count("select count(*) from mileage_wallets w"
                + " where w.balance <> (select sum(t.amount) from mileage_transactions t where t.user_id = w.user_id)")).isZero();
    }

    @Test
    void rerunSkipsAndPartialDemoUsersAbortWithoutChanges() {
        List<Long> before = snapshot();

        seeder.run(null);
        assertThat(snapshot()).isEqualTo(before);

        jdbcTemplate.update("update users set handle = 'moved20' where handle = 'demo20'");
        try {
            assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class);
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            jdbcTemplate.update("update users set handle = 'demo20' where handle = 'moved20'");
        }
    }

    private void assertEveryUserHas(String table, String userColumn, long expected) {
        assertThat(count("select count(*) from (select " + userColumn + " from " + table
                + " group by " + userColumn + " having count(*) = " + expected + ") t")).isEqualTo(20);
    }

    private List<Long> snapshot() {
        return List.of(
                "users", "posts", "post_comments", "follows", "post_likes", "post_reposts", "post_shares",
                "post_bookmarks", "bookmark_collection_items", "direct_messages", "mentoring_applications",
                "mentoring_reviews", "notifications", "mileage_transactions"
        ).stream().map(table -> count("select count(*) from " + table)).toList();
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private List<String> strings(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("SEED_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("SEED_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("SEED_POSTGRES_TEST_PASSWORD", true);
            String schema = "demo_seed_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL demo seed test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL demo seed test schema.", error);
            }
        }
    }
}
