package com.gamerin.backend.global.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "MESSAGE_POSTGRES_TEST_URL", matches = ".+")
class DirectMessageColumnDefaultsMigrationTest {

    private static final List<DefaultColumn> DEFAULT_COLUMNS = List.of(
            new DefaultColumn("message_conversations", "id"),
            new DefaultColumn("message_conversations", "type"),
            new DefaultColumn("message_conversations", "created_at"),
            new DefaultColumn("message_conversations", "updated_at"),
            new DefaultColumn("message_participants", "id"),
            new DefaultColumn("message_participants", "joined_at"),
            new DefaultColumn("direct_messages", "id"),
            new DefaultColumn("direct_messages", "created_at"),
            new DefaultColumn("direct_message_attachments", "id"),
            new DefaultColumn("direct_message_attachments", "sort_order"),
            new DefaultColumn("direct_message_attachments", "created_at")
    );

    @ParameterizedTest
    @ValueSource(strings = {"all", "partial", "none"})
    void migrationRepairsExistingTablesWithoutChangingDataOrConstraints(String missingDefaults) throws Exception {
        try (Fixture fixture = new Fixture()) {
            // Prepare pre-existing tables before Flyway runs V10, as on a DB originally created by Hibernate.
            ScriptUtils.executeSqlScript(fixture.connection,
                    new ClassPathResource("db/migration/V10__add_direct_message_schema.sql"));
            for (DefaultColumn column : DEFAULT_COLUMNS) {
                if (missingDefaults.equals("all")
                        || (missingDefaults.equals("partial") && column.name().endsWith("_at"))) {
                    fixture.execute("alter table " + column.table() + " alter column " + column.name() + " drop default");
                }
            }
            fixture.flyway("27").migrate();
            assertThat(fixture.defaultCount()).isEqualTo(switch (missingDefaults) {
                case "all" -> 0;
                case "partial" -> 6;
                default -> DEFAULT_COLUMNS.size();
            });
            UUID userId = fixture.addUser();
            fixture.seedExistingMessages(userId);
            String dataBefore = fixture.dataSnapshot();
            String constraintsBefore = fixture.constraintSnapshot();
            String indexesBefore = fixture.indexSnapshot();

            if (!missingDefaults.equals("none")) {
                assertThatThrownBy(() -> fixture.execute("""
                        insert into message_conversations(direct_key, type) values ('broken', 'DIRECT')
                        on conflict (direct_key) do nothing
                        """))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23502"));
                assertThatThrownBy(() -> fixture.execute("""
                        insert into message_participants(conversation_id, user_id)
                        values ('00000000-0000-0000-0000-000000000001', ?)
                        on conflict (conversation_id, user_id) do nothing
                        """, userId))
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23502"));
            }

            Flyway migration = fixture.flyway("29");
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(migration.info().current().getVersion().toString()).isEqualTo("29");
            assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(fixture.dataSnapshot()).isEqualTo(dataBefore);
            assertThat(fixture.constraintSnapshot()).isEqualTo(constraintsBefore);
            assertThat(fixture.indexSnapshot()).isEqualTo(indexesBefore);
            fixture.assertDefaultInserts(userId);
        }
    }

    @Test
    void freshDatabaseMigratesAndSupportsDefaultDependentInserts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Flyway migration = fixture.flyway("29");
            migration.migrate();
            assertThat(migration.validateWithResult().validationSuccessful).isTrue();
            fixture.assertDefaultInserts(fixture.addUser());
        }
    }

    private record DefaultColumn(String table, String name) {
    }

    private static class Fixture implements AutoCloseable {
        private final String schema = "message_defaults_migration_" + UUID.randomUUID().toString().replace("-", "");
        private final String url = System.getenv("MESSAGE_POSTGRES_TEST_URL");
        private final String username = System.getenv("MESSAGE_POSTGRES_TEST_USERNAME");
        private final String password = System.getenv("MESSAGE_POSTGRES_TEST_PASSWORD");
        private final Connection connection;

        Fixture() throws SQLException {
            connection = DriverManager.getConnection(url, username, password);
            execute("create schema " + schema);
            try {
                flyway("9").migrate();
                execute("set search_path = " + schema);
            } catch (RuntimeException | SQLException failure) {
                close();
                throw failure;
            }
        }

        Flyway flyway(String target) {
            return Flyway.configure().dataSource(url, username, password)
                    .schemas(schema).defaultSchema(schema).target(target).load();
        }

        UUID addUser() throws SQLException {
            UUID id = UUID.randomUUID();
            execute("insert into users(id, handle, nickname, role, status) values (?, ?, 'Legacy', 'USER', 'ACTIVE')",
                    id, id.toString());
            return id;
        }

        void seedExistingMessages(UUID userId) throws SQLException {
            OffsetDateTime timestamp = OffsetDateTime.parse("2025-01-01T00:00:00Z");
            UUID conversationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID messageId = UUID.randomUUID();
            execute("""
                    insert into message_conversations(id, type, direct_key, created_at, updated_at)
                    values (?, 'DIRECT', 'existing', ?, ?)
                    """, conversationId, timestamp, timestamp);
            execute("""
                    insert into message_participants(id, conversation_id, user_id, joined_at)
                    values (?, ?, ?, ?)
                    """, UUID.randomUUID(), conversationId, userId, timestamp);
            execute("""
                    insert into direct_messages(id, conversation_id, sender_id, content, created_at)
                    values (?, ?, ?, 'existing message', ?)
                    """, messageId, conversationId, userId, timestamp);
            execute("""
                    insert into direct_message_attachments(id, message_id, attachment_type, file_name, file_url,
                                                          sort_order, created_at)
                    values (?, ?, 'IMAGE', 'existing.jpg', '/existing.jpg', 3, ?)
                    """, UUID.randomUUID(), messageId, timestamp);
        }

        void assertDefaultInserts(UUID userId) throws SQLException {
            assertThat(defaultCount()).isEqualTo(DEFAULT_COLUMNS.size());
            UUID conversationId;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    insert into message_conversations(direct_key, type) values ('new', 'DIRECT')
                    on conflict (direct_key) do nothing returning id, created_at, updated_at
                    """)) {
                assertThat(rows.next()).isTrue();
                conversationId = rows.getObject("id", UUID.class);
                assertThat(conversationId).isNotNull();
                assertThat(rows.getObject("created_at", OffsetDateTime.class)).isNotNull();
                assertThat(rows.getObject("updated_at", OffsetDateTime.class)).isNotNull();
            }
            assertThat(execute("""
                    insert into message_conversations(direct_key, type) values ('new', 'DIRECT')
                    on conflict (direct_key) do nothing
                    """)).isZero();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    insert into message_conversations(direct_key) values ('default-type') returning id, type
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("id", UUID.class)).isNotEqualTo(conversationId);
                assertThat(rows.getString("type")).isEqualTo("DIRECT");
            }
            try (var statement = connection.prepareStatement("""
                    insert into message_participants(conversation_id, user_id) values (?, ?)
                    on conflict (conversation_id, user_id) do nothing returning id, joined_at
                    """)) {
                bind(statement, conversationId, userId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("id", UUID.class)).isNotNull();
                    assertThat(rows.getObject("joined_at", OffsetDateTime.class)).isNotNull();
                }
            }
            assertThat(execute("""
                    insert into message_participants(conversation_id, user_id) values (?, ?)
                    on conflict (conversation_id, user_id) do nothing
                    """, conversationId, userId)).isZero();
            UUID messageId;
            try (var statement = connection.prepareStatement("""
                    insert into direct_messages(conversation_id, sender_id, content)
                    values (?, ?, 'new message') returning id, created_at
                    """)) {
                bind(statement, conversationId, userId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    messageId = rows.getObject("id", UUID.class);
                    assertThat(messageId).isNotNull();
                    assertThat(rows.getObject("created_at", OffsetDateTime.class)).isNotNull();
                }
            }
            try (var statement = connection.prepareStatement("""
                    insert into direct_message_attachments(message_id, attachment_type, file_name, file_url)
                    values (?, 'IMAGE', 'new.jpg', '/new.jpg') returning id, sort_order, created_at
                    """)) {
                bind(statement, messageId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("id", UUID.class)).isNotNull();
                    assertThat(rows.getInt("sort_order")).isZero();
                    assertThat(rows.getObject("created_at", OffsetDateTime.class)).isNotNull();
                }
            }
            assertThatThrownBy(() -> execute("""
                    insert into direct_message_attachments(message_id, attachment_type, file_name, file_url)
                    values (?, 'IMAGE', 'duplicate.jpg', '/duplicate.jpg')
                    """, messageId))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
        }

        int defaultCount() throws SQLException {
            int count = 0;
            for (DefaultColumn column : DEFAULT_COLUMNS) {
                try (var statement = connection.prepareStatement("""
                        select column_default from information_schema.columns
                        where table_schema = ? and table_name = ? and column_name = ?
                        """)) {
                    bind(statement, schema, column.table(), column.name());
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        if (rows.getString(1) != null) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        String dataSnapshot() throws SQLException {
            StringBuilder result = new StringBuilder();
            for (String table : List.of("message_conversations", "message_participants", "direct_messages",
                    "direct_message_attachments")) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "select jsonb_agg(to_jsonb(t) order by id)::text from " + table + " t")) {
                    rows.next();
                    result.append(rows.getString(1));
                }
            }
            return result.toString();
        }

        String constraintSnapshot() throws SQLException {
            try (var statement = connection.prepareStatement("""
                    select jsonb_agg(jsonb_build_array(c.relname, a.attname, a.attnotnull) order by c.relname, a.attnum)::text
                    from pg_attribute a join pg_class c on c.oid = a.attrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relname in
                        ('message_conversations', 'message_participants', 'direct_messages', 'direct_message_attachments')
                    and a.attnum > 0 and not a.attisdropped
                    """)) {
                bind(statement, schema);
                String result;
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    result = rows.getString(1);
                }
                try (var constraints = connection.prepareStatement("""
                        select jsonb_agg(jsonb_build_array(c.relname, k.conname, pg_get_constraintdef(k.oid))
                                         order by c.relname, k.conname)::text
                        from pg_constraint k join pg_class c on c.oid = k.conrelid
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = ? and c.relname in
                            ('message_conversations', 'message_participants', 'direct_messages', 'direct_message_attachments')
                        """)) {
                    bind(constraints, schema);
                    try (var rows = constraints.executeQuery()) {
                        rows.next();
                        return result + rows.getString(1);
                    }
                }
            }
        }

        int execute(String sql, Object... values) throws SQLException {
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                return statement.executeUpdate();
            }
        }

        String indexSnapshot() throws SQLException {
            try (var statement = connection.prepareStatement("""
                    select jsonb_agg(jsonb_build_array(tablename, indexname, indexdef) order by tablename, indexname)::text
                    from pg_indexes where schemaname = ? and tablename in
                        ('message_conversations', 'message_participants', 'direct_messages', 'direct_message_attachments')
                    """)) {
                bind(statement, schema);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            }
        }

        private void bind(java.sql.PreparedStatement statement, Object... values) throws SQLException {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
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
