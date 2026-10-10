package com.gamerin.backend.global.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "GAME_STATS_POSTGRES_TEST_URL", matches = ".+")
class GameConnectionVersionsMigrationTest {

    @Test
    void migrationPreservesExistingJsonAndInitializesOnlyConnectionVersions() throws Exception {
        String schema = "game_stats_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("GAME_STATS_POSTGRES_TEST_URL");
        String username = System.getenv("GAME_STATS_POSTGRES_TEST_USERNAME");
        String password = System.getenv("GAME_STATS_POSTGRES_TEST_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
            try {
                Flyway.configure().dataSource(url, username, password)
                        .schemas(schema).defaultSchema(schema).target("19").load().migrate();
                statement.execute("set search_path = " + schema);
                UUID userId = UUID.randomUUID();
                try (var user = connection.prepareStatement(
                        "insert into users(id, handle, nickname, role, status) values (?, 'legacy', 'Legacy', 'USER', 'ACTIVE')")) {
                    user.setObject(1, userId);
                    user.executeUpdate();
                }
                String gameStats = """
                        {"RIOT":{"connected":true,"puuid":"legacy-puuid","riotId":"legacy#KR1"},
                         "LOL":{"connected":true,"tierLabel":"GOLD I","games":10},
                         "OTHER":{"custom":null}}
                        """;
                try (var profile = connection.prepareStatement(
                        "insert into user_profiles(user_id, game_stats, bio) values (?, cast(? as jsonb), 'existing bio')")) {
                    profile.setObject(1, userId);
                    profile.setString(2, gameStats);
                    profile.executeUpdate();
                }
                Flyway flyway = Flyway.configure().dataSource(url, username, password)
                        .schemas(schema).defaultSchema(schema).target("21").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("21");
                assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
                try (var query = connection.prepareStatement("""
                        select game_stats = cast(? as jsonb), game_connection_versions::text, bio
                        from user_profiles where user_id = ?
                        """)) {
                    query.setString(1, gameStats);
                    query.setObject(2, userId);
                    try (var result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isTrue();
                        assertThat(result.getString(2)).isEqualTo("{}");
                        assertThat(result.getString(3)).isEqualTo("existing bio");
                    }
                }
            } finally {
                statement.execute("set search_path = public");
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
