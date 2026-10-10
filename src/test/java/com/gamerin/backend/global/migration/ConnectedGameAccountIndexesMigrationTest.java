package com.gamerin.backend.global.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "GAME_STATS_POSTGRES_TEST_URL", matches = ".+")
class ConnectedGameAccountIndexesMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"R6", "RIOT"})
    void migrationPreservesDataAndConstrainsOnlyConnectedIdentifiers(String game) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addProfile(stats(game, "true", "SharedAccount"));
            UUID disconnected = fixture.addProfile(stats(game, "false", "SharedAccount"));
            fixture.addProfile(stats(game, "null", "SharedAccount"));
            fixture.addProfile("{}");
            fixture.addProfile(stats(game, "true", null));
            fixture.addProfile(stats(game, "true", null));
            fixture.addProfile("{\"" + game + "\":{\"connected\":true}}");
            String before = fixture.snapshot();

            Flyway migration = fixture.flyway("23");
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(migration.info().current().getVersion().toString()).isEqualTo("23");
            assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            assertThat(fixture.snapshot()).isEqualTo(before);
            assertThat(fixture.indexCount()).isEqualTo(2);
            String duplicate = game.equals("R6") ? "SHAREDACCOUNT" : "SharedAccount";
            assertThatThrownBy(() -> fixture.updateStats(disconnected, stats(game, "true", duplicate)))
                    .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23505"));
            assertThat(fixture.snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"R6", "RIOT"})
    void existingDuplicateOwnersAbortMigrationWithoutReassigningAccounts(String game) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addProfile(stats(game, "true", "SharedAccount"));
            String duplicate = game.equals("R6") ? "SHAREDACCOUNT" : "SharedAccount";
            UUID second = fixture.addProfile(stats(game, "true", duplicate));
            String before = fixture.snapshot();

            assertThatThrownBy(() -> fixture.flyway("23").migrate()).isInstanceOf(FlywayException.class);
            assertThat(fixture.snapshot()).isEqualTo(before);
            assertThat(fixture.indexCount()).isZero();
            assertThat(fixture.flyway("23").info().current().getVersion().toString()).isEqualTo("21");

            // Simulate an explicitly chosen resolution in the fixture, never implicit migration data cleanup.
            fixture.updateStats(second, stats(game, "false", duplicate));
            assertThat(fixture.flyway("23").migrate().migrationsExecuted).isEqualTo(1);
            assertThat(fixture.indexCount()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"R6", "RIOT"})
    void invalidLegacyConnectedFlagAbortsMigrationWithoutChangingData(String game) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.addProfile(stats(game, "\"invalid\"", "SharedAccount"));
            String before = fixture.snapshot();
            assertThatThrownBy(() -> fixture.flyway("23").migrate()).isInstanceOf(FlywayException.class);
            assertThat(fixture.snapshot()).isEqualTo(before);
            assertThat(fixture.indexCount()).isZero();
        }
    }

    private static String stats(String game, String connected, String identifier) {
        String field = game.equals("R6") ? "accountId" : "puuid";
        String value = identifier == null ? "null" : "\"" + identifier + "\"";
        return "{\"" + game + "\":{\"connected\":" + connected + ",\"" + field + "\":" + value
                + "},\"OTHER\":{\"custom\":null},\"LOL\":{\"games\":42}}";
    }

    private static class Fixture implements AutoCloseable {
        private final String schema = "game_account_migration_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = System.getenv("GAME_STATS_POSTGRES_TEST_URL");
        private final String username = System.getenv("GAME_STATS_POSTGRES_TEST_USERNAME");
        private final String password = System.getenv("GAME_STATS_POSTGRES_TEST_PASSWORD");
        private final Connection connection;

        Fixture() throws SQLException {
            connection = DriverManager.getConnection(url, username, password);
            try (var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                try {
                    flyway("21").migrate();
                    statement.execute("set search_path = " + schema);
                } catch (RuntimeException | SQLException failure) {
                    statement.execute("drop schema " + schema + " cascade");
                    connection.close();
                    throw failure;
                }
            }
        }

        Flyway flyway(String target) {
            return Flyway.configure().dataSource(url, username, password)
                    .schemas(schema).defaultSchema(schema).target(target).load();
        }

        UUID addProfile(String stats) throws SQLException {
            UUID id = UUID.randomUUID();
            try (var user = connection.prepareStatement(
                    "insert into users(id, handle, nickname, role, status) values (?, ?, 'Legacy', 'USER', 'ACTIVE')")) {
                user.setObject(1, id);
                user.setString(2, id.toString());
                user.executeUpdate();
            }
            try (var profile = connection.prepareStatement("""
                    insert into user_profiles(user_id, game_stats, game_connection_versions, bio)
                    values (?, cast(? as jsonb), '{"R6":5,"RIOT":3}', 'existing bio')
                    """)) {
                profile.setObject(1, id);
                profile.setString(2, stats);
                profile.executeUpdate();
            }
            return id;
        }

        void updateStats(UUID id, String stats) throws SQLException {
            try (var update = connection.prepareStatement("update user_profiles set game_stats = cast(? as jsonb) where user_id = ?")) {
                update.setString(1, stats);
                update.setObject(2, id);
                update.executeUpdate();
            }
        }

        String snapshot() throws SQLException {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select jsonb_agg(to_jsonb(p) order by user_id)::text from user_profiles p")) {
                rows.next();
                return rows.getString(1);
            }
        }

        int indexCount() throws SQLException {
            try (var query = connection.prepareStatement("""
                    select count(*) from pg_indexes where schemaname = ?
                    and indexname in ('uq_user_profiles_connected_r6_account', 'uq_user_profiles_connected_riot_puuid')
                    """)) {
                query.setString(1, schema);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
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
