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

class BookmarkCollectionMigrationTest {

    @Test
    void migrationPreservesExistingBookmarksAndAppliesCascadePolicy() throws Exception {
        String url = "jdbc:h2:mem:bookmark_migration_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        UUID userId = UUID.randomUUID();
        UUID bookmarkId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("create table users (id uuid primary key)");
            connection.createStatement().execute("""
                    create table post_bookmarks (
                        id uuid primary key,
                        user_id uuid not null references users(id)
                    )
                    """);
            execute(connection, "insert into users(id) values (?)", userId);
            execute(
                    connection,
                    "insert into post_bookmarks(id, user_id) values (?, ?)",
                    bookmarkId,
                    userId
            );
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("14")
                .target("15")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(count(connection, "select count(*) from post_bookmarks where id = ?", bookmarkId))
                    .isEqualTo(1);
            assertThat(count(connection, "select count(*) from bookmark_collection_items"))
                    .isZero();

            UUID collectionId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();
            insertCollection(connection, collectionId, userId, "Clips", "clips");
            execute(
                    connection,
                    """
                    insert into bookmark_collection_items(id, collection_id, post_bookmark_id)
                    values (?, ?, ?)
                    """,
                    itemId,
                    collectionId,
                    bookmarkId
            );

            assertThatThrownBy(() -> insertCollection(
                    connection,
                    UUID.randomUUID(),
                    userId,
                    "CLIPS",
                    "clips"
            )).isInstanceOf(SQLException.class);

            execute(connection, "delete from bookmark_collections where id = ?", collectionId);
            assertThat(count(connection, "select count(*) from post_bookmarks where id = ?", bookmarkId))
                    .isEqualTo(1);
            assertThat(count(connection, "select count(*) from bookmark_collection_items where id = ?", itemId))
                    .isZero();

            UUID nextCollectionId = UUID.randomUUID();
            UUID nextItemId = UUID.randomUUID();
            insertCollection(connection, nextCollectionId, userId, "Ranked", "ranked");
            execute(
                    connection,
                    """
                    insert into bookmark_collection_items(id, collection_id, post_bookmark_id)
                    values (?, ?, ?)
                    """,
                    nextItemId,
                    nextCollectionId,
                    bookmarkId
            );
            execute(connection, "delete from post_bookmarks where id = ?", bookmarkId);
            assertThat(count(
                    connection,
                    "select count(*) from bookmark_collection_items where id = ?",
                    nextItemId
            )).isZero();
        }
    }

    private void insertCollection(
            Connection connection,
            UUID collectionId,
            UUID userId,
            String name,
            String normalizedName
    ) throws SQLException {
        execute(
                connection,
                """
                insert into bookmark_collections(id, user_id, name, normalized_name)
                values (?, ?, ?, ?)
                """,
                collectionId,
                userId,
                name,
                normalizedName
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
