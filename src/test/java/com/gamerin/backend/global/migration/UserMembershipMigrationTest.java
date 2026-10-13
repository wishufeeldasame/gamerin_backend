package com.gamerin.backend.global.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "PAYMENT_POSTGRES_TEST_URL", matches = ".+")
class UserMembershipMigrationTest {

    @Test
    void missingMembershipMigrationAfterPaymentMigrationRequiresExplicitOutOfOrder(@TempDir Path migrations)
            throws Exception {
        // Reproduce the payment branch before #87: V35 exists, but V33 does not.
        try (var scripts = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path script : scripts.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .filter(path -> !path.getFileName().toString().startsWith("V33__")).toList()) {
                Files.copy(script, migrations.resolve(script.getFileName()));
            }
        }

        try (Fixture fixture = new Fixture()) {
            Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password)
                    .schemas(fixture.schema).defaultSchema(fixture.schema)
                    .locations("filesystem:" + migrations.toAbsolutePath()).target("35").load().migrate();
            UUID existingUser = fixture.addUser();

            assertThatThrownBy(() -> fixture.flyway("35").migrate()).isInstanceOf(FlywayException.class);
            assertThat(fixture.membershipTableExists()).isFalse();

            Flyway catchUp = Flyway.configure().dataSource(fixture.url, fixture.username, fixture.password)
                    .schemas(fixture.schema).defaultSchema(fixture.schema)
                    .target("35").outOfOrder(true).load();
            assertThat(catchUp.migrate().migrationsExecuted).isEqualTo(1);

            assertThat(fixture.nickname(existingUser)).isEqualTo("Existing user");
            fixture.assertMembershipConstraints(existingUser);
            Flyway normal = fixture.flyway("35");
            assertThat(normal.validateWithResult().validationSuccessful).isTrue();
            assertThat(normal.migrate().migrationsExecuted).isZero();
            assertThat(normal.info().current().getVersion().toString()).isEqualTo("35");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndUpgradedSchemasEnforceMembershipConstraintsWithoutChangingUsers(boolean upgrade) throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID existingUser = null;
            if (upgrade) {
                fixture.flyway("29").migrate();
                existingUser = fixture.addUser();
            }

            Flyway migration = fixture.flyway("33");
            migration.migrate();

            assertThat(migration.info().current().getVersion().toString()).isEqualTo("33");
            assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            assertThat(migration.migrate().migrationsExecuted).isZero();
            if (existingUser != null) {
                assertThat(fixture.nickname(existingUser)).isEqualTo("Existing user");
            }
            fixture.assertMembershipConstraints(existingUser == null ? fixture.addUser() : existingUser);
        }
    }

    private static class Fixture implements AutoCloseable {
        private final String schema = "membership_migration_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = System.getenv("PAYMENT_POSTGRES_TEST_URL");
        private final String username = System.getenv("PAYMENT_POSTGRES_TEST_USERNAME");
        private final String password = System.getenv("PAYMENT_POSTGRES_TEST_PASSWORD");
        private final Connection connection;

        Fixture() throws SQLException {
            connection = DriverManager.getConnection(url, username, password);
            try {
                execute("create schema " + schema);
                execute("set search_path = " + schema);
            } catch (SQLException failure) {
                connection.close();
                throw failure;
            }
        }

        Flyway flyway(String target) {
            return Flyway.configure().dataSource(url, username, password)
                    .schemas(schema).defaultSchema(schema).target(target).load();
        }

        UUID addUser() throws SQLException {
            UUID userId = UUID.randomUUID();
            execute("""
                    insert into users(id, handle, nickname, role, status)
                    values (?, ?, 'Existing user', 'USER', 'ACTIVE')
                    """, userId, userId.toString());
            return userId;
        }

        String nickname(UUID userId) throws SQLException {
            try (var statement = connection.prepareStatement("select nickname from users where id = ?")) {
                statement.setObject(1, userId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return rows.getString(1);
                }
            }
        }

        boolean membershipTableExists() throws SQLException {
            try (var statement = connection.prepareStatement("select to_regclass('user_memberships')");
                    var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1) != null;
            }
        }

        void assertMembershipConstraints(UUID userId) throws SQLException {
            OffsetDateTime expiresAt = OffsetDateTime.parse("2026-11-12T09:00:00.123456+09:00");
            try (var statement = connection.prepareStatement("""
                    insert into user_memberships(user_id, expires_at) values (?, ?)
                    returning expires_at, updated_at
                    """)) {
                statement.setObject(1, userId);
                statement.setObject(2, expiresAt);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("expires_at", OffsetDateTime.class).toInstant())
                            .isEqualTo(expiresAt.toInstant());
                    assertThat(rows.getObject("updated_at", OffsetDateTime.class)).isNotNull();
                }
            }
            assertConstraint("23505", "insert into user_memberships(user_id, expires_at) values (?, ?)",
                    userId, expiresAt);
            assertConstraint("23503", "insert into user_memberships(user_id, expires_at) values (?, ?)",
                    UUID.randomUUID(), expiresAt);
            UUID anotherUser = addUser();
            assertConstraint("23502", "insert into user_memberships(user_id) values (?)", anotherUser);
            assertConstraint("23502", """
                    insert into user_memberships(user_id, expires_at, updated_at) values (?, ?, null)
                    """, anotherUser, expiresAt);
        }

        void assertConstraint(String sqlState, String sql, Object... values) {
            assertThatThrownBy(() -> execute(sql, values))
                    .isInstanceOfSatisfying(SQLException.class,
                            failure -> assertThat(failure.getSQLState()).isEqualTo(sqlState));
        }

        int execute(String sql, Object... values) throws SQLException {
            try (var statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < values.length; index++) {
                    statement.setObject(index + 1, values[index]);
                }
                return statement.executeUpdate();
            }
        }

        @Override
        public void close() throws SQLException {
            try (connection; var statement = connection.createStatement()) {
                statement.execute("set search_path = public");
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
