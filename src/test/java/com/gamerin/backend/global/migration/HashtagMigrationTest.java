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

class HashtagMigrationTest {

    @Test
    void migrationAddsUniqueHashtagsAndCascadePostRelations() throws Exception {
        String url = "jdbc:h2:mem:hashtag_migration_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        UUID postId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("create table posts (id uuid primary key)");
            execute(connection, "insert into posts(id) values (?)", postId);
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("15")
                .target("16")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            UUID hashtagId = UUID.randomUUID();
            UUID lowercaseHashtagId = UUID.randomUUID();
            UUID relationId = UUID.randomUUID();
            insertHashtag(connection, hashtagId, "PUBG", "PUBG");
            insertHashtag(connection, lowercaseHashtagId, "pubg", "pubg");
            insertPostHashtag(connection, relationId, postId, hashtagId);

            assertThatThrownBy(() -> insertHashtag(
                    connection,
                    UUID.randomUUID(),
                    "PUBG",
                    "PUBG"
            )).isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertPostHashtag(
                    connection,
                    UUID.randomUUID(),
                    postId,
                    hashtagId
            )).isInstanceOf(SQLException.class);

            execute(connection, "delete from posts where id = ?", postId);
            assertThat(count(connection, "select count(*) from post_hashtags where id = ?", relationId))
                    .isZero();
            assertThat(count(connection, "select count(*) from hashtags where id = ?", hashtagId))
                    .isEqualTo(1);
        }
    }

    private void insertHashtag(
            Connection connection,
            UUID hashtagId,
            String displayName,
            String normalizedName
    ) throws SQLException {
        execute(
                connection,
                "insert into hashtags(id, display_name, normalized_name) values (?, ?, ?)",
                hashtagId,
                displayName,
                normalizedName
        );
    }

    private void insertPostHashtag(
            Connection connection,
            UUID relationId,
            UUID postId,
            UUID hashtagId
    ) throws SQLException {
        execute(
                connection,
                "insert into post_hashtags(id, post_id, hashtag_id) values (?, ?, ?)",
                relationId,
                postId,
                hashtagId
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
