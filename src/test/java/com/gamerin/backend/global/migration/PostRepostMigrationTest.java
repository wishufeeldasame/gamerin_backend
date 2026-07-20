package com.gamerin.backend.global.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PostRepostMigrationTest {

    @Test
    void migrationAddsUniqueRepostsAndCascadesPostAndUserDeletion() throws Exception {
        String url = "jdbc:h2:mem:repost_migration_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("create table users (id uuid primary key)");
            connection.createStatement().execute("create table posts (id uuid primary key)");
            execute(connection, "insert into users(id) values (?)", userId);
            execute(connection, "insert into posts(id) values (?)", postId);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("15")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            UUID repostId = UUID.randomUUID();
            insertRepost(connection, repostId, postId, userId);

            assertThatThrownBy(() -> insertRepost(
                    connection,
                    UUID.randomUUID(),
                    postId,
                    userId
            )).isInstanceOf(SQLException.class);

            execute(connection, "delete from posts where id = ?", postId);
            assertThat(count(connection, "select count(*) from post_reposts where id = ?", repostId))
                    .isZero();

            UUID nextPostId = UUID.randomUUID();
            UUID nextRepostId = UUID.randomUUID();
            execute(connection, "insert into posts(id) values (?)", nextPostId);
            insertRepost(connection, nextRepostId, nextPostId, userId);
            execute(connection, "delete from users where id = ?", userId);
            assertThat(count(connection, "select count(*) from post_reposts where id = ?", nextRepostId))
                    .isZero();
        }
    }

    private void insertRepost(
            Connection connection,
            UUID repostId,
            UUID postId,
            UUID userId
    ) throws SQLException {
        execute(
                connection,
                "insert into post_reposts(id, post_id, user_id) values (?, ?, ?)",
                repostId,
                postId,
                userId
        );
    }

    private long count(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
