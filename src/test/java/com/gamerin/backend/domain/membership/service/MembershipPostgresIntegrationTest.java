package com.gamerin.backend.domain.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.membership.repository.MembershipGrantRepository;
import com.gamerin.backend.domain.payment.repository.PaymentRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "PAYMENT_POSTGRES_TEST_URL", matches = ".+")
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MembershipPostgresIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T00:00:00.123456Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static PostgresSchema postgresSchema;

    @Autowired
    private UserMembershipRepository membershipRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private MembershipGrantRepository grantRepository;
    @Autowired private PaymentRepository paymentRepository;

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
    static void removeTemporarySchema() throws SQLException {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "1,true"})
    void singleAndBulkLookupUseTheSameStrictMicrosecondBoundary(long microseconds, boolean active) {
        User user = saveUser();
        saveMembership(user, NOW.plusNanos(microseconds * 1000));
        MembershipService service = service();

        assertThat(service.isActive(user.getId())).isEqualTo(active);
        assertThat(service.findActiveUserIds(List.of(user.getId())))
                .isEqualTo(active ? Set.of(user.getId()) : Set.of());
    }

    @Test
    void bulkLookupQueriesOnceAndIgnoresInactiveMissingAndDuplicateUsers() {
        User active = saveUser();
        User farFuture = saveUser();
        User expired = saveUser();
        User withoutMembership = saveUser();
        saveMembership(active, NOW.plusDays(1));
        saveMembership(farFuture, NOW.plusYears(100));
        saveMembership(expired, NOW.minusNanos(1000));
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.clear();

        Set<UUID> activeIds = service().findActiveUserIds(
                List.of(active.getId(), farFuture.getId(), expired.getId(), withoutMembership.getId(),
                        UUID.randomUUID(), active.getId()));

        assertThat(activeIds).containsExactlyInAnyOrder(active.getId(), farFuture.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    void emptyLookupDoesNotExecuteSql() {
        Statistics statistics = statistics();
        statistics.clear();

        assertThat(service().findActiveUserIds(List.of())).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void membershipMappingPreservesInstantsAndUpdatesItsTimestamp() {
        User user = saveUser();
        OffsetDateTime expiresAt = NOW.plusDays(30).withOffsetSameInstant(ZoneOffset.ofHours(9));
        UserMembership membership = saveMembership(user, expiresAt);
        assertThat(membership.getUserId()).isEqualTo(user.getId());
        assertThat(membership.getUpdatedAt()).isNotNull();
        entityManager.clear();

        UserMembership reloaded = membershipRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getExpiresAt().toInstant()).isEqualTo(expiresAt.toInstant());
        assertThat(service().isActive(user.getId())).isTrue();

        OffsetDateTime previousUpdate = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        jdbcTemplate.update("update user_memberships set updated_at = ? where user_id = ?",
                previousUpdate, user.getId());
        entityManager.clear();
        UserMembership updated = membershipRepository.findById(user.getId()).orElseThrow();
        updated.setExpiresAt(expiresAt.plusDays(30));
        membershipRepository.flush();
        entityManager.clear();

        UserMembership persisted = membershipRepository.findById(user.getId()).orElseThrow();
        assertThat(persisted.getExpiresAt().toInstant()).isEqualTo(expiresAt.plusDays(30).toInstant());
        assertThat(persisted.getUpdatedAt().toInstant()).isAfter(previousUpdate.toInstant());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void sharedMembershipPassPeriodIsPersistedFromTheSelectedStart(int startOffsetDays) {
        User user = saveUser();
        UserMembership membership = UserMembership.create(user, NOW);
        OffsetDateTime grantedFrom = NOW.plusDays(startOffsetDays)
                .withOffsetSameInstant(ZoneOffset.ofHours(9));

        membership.applyMembershipPassFrom(grantedFrom);
        membershipRepository.saveAndFlush(membership);
        entityManager.clear();

        UserMembership persisted = membershipRepository.findById(user.getId()).orElseThrow();
        assertThat(persisted.getExpiresAt().toInstant())
                .isEqualTo(NOW.plusDays(startOffsetDays + 30).toInstant());
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    private MembershipService service() {
        return new MembershipService(membershipRepository, grantRepository, paymentRepository, userRepository, FIXED_CLOCK);
    }

    private User saveUser() {
        String handle = "membership" + UUID.randomUUID().toString().replace("-", "");
        User user = User.createLocal(handle + "@example.com", handle, "Member", "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private UserMembership saveMembership(User user, OffsetDateTime expiresAt) {
        return membershipRepository.saveAndFlush(UserMembership.create(user, expiresAt));
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        static PostgresSchema create() {
            String url = System.getenv("PAYMENT_POSTGRES_TEST_URL");
            String username = System.getenv("PAYMENT_POSTGRES_TEST_USERNAME");
            String password = System.getenv("PAYMENT_POSTGRES_TEST_PASSWORD");
            String schema = "membership_core_" + UUID.randomUUID().toString().replace("-", "");
            PostgresSchema fixture = new PostgresSchema(url, username, password, schema);
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                try {
                    // Upgrade an existing pre-membership schema; Spring must apply V33 before JPA validation.
                    Flyway.configure().dataSource(url, username, password)
                            .schemas(schema).defaultSchema(schema).target("29").load().migrate();
                } catch (RuntimeException failure) {
                    statement.execute("drop schema " + schema + " cascade");
                    throw failure;
                }
                return fixture;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not prepare the PostgreSQL membership test schema.", failure);
            }
        }

        void close() throws SQLException {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
