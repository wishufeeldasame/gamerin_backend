package com.gamerin.backend.domain.message.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "MESSAGE_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class MessageConversationPostgresIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private MessageService messageService;
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
    static void removeTemporarySchema() throws SQLException {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    void upgradedLegacySchemaSupportsConversationCreationAndReusesBothParticipants() {
        User sender = saveUser("sender");
        User recipient = saveUser("recipient");
        CustomUserPrincipal senderPrincipal = CustomUserPrincipal.from(sender);
        CustomUserPrincipal recipientPrincipal = CustomUserPrincipal.from(recipient);

        var response = messageService.createConversation(senderPrincipal,
                new CreateConversationRequest(null, recipient.getId()));
        var repeated = messageService.createConversation(senderPrincipal,
                new CreateConversationRequest(null, recipient.getId()));
        var reverse = messageService.createConversation(recipientPrincipal,
                new CreateConversationRequest(null, sender.getId()));

        assertThat(response.id()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
        assertThat(response.recipient().id()).isEqualTo(recipient.getId());
        assertThat(repeated.id()).isEqualTo(response.id());
        assertThat(reverse.id()).isEqualTo(response.id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_conversations", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                select created_at from message_conversations where id = ?
                """, OffsetDateTime.class, response.id())).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from message_participants
                where conversation_id = ? and id is not null and joined_at is not null
                  and user_id in (?, ?)
                """, Long.class, response.id(), sender.getId(), recipient.getId())).isEqualTo(2L);
    }

    private User saveUser(String prefix) {
        String handle = prefix + UUID.randomUUID().toString().replace("-", "");
        User user = User.createLocal(handle + "@example.com", handle, prefix, "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        static PostgresSchema create() {
            String url = System.getenv("MESSAGE_POSTGRES_TEST_URL");
            String username = System.getenv("MESSAGE_POSTGRES_TEST_USERNAME");
            String password = System.getenv("MESSAGE_POSTGRES_TEST_PASSWORD");
            String schema = "message_conversation_" + UUID.randomUUID().toString().replace("-", "");
            PostgresSchema fixture = new PostgresSchema(url, username, password, schema);
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                try {
                    Flyway.configure().dataSource(url, username, password)
                            .schemas(schema).defaultSchema(schema).target("27").load().migrate();
                    statement.execute("set search_path = " + schema);
                    // Hibernate validation does not detect these missing defaults; V29 must repair them at startup.
                    statement.execute("""
                            alter table message_conversations
                                alter column id drop default,
                                alter column created_at drop default,
                                alter column updated_at drop default
                            """);
                    statement.execute("""
                            alter table message_participants
                                alter column id drop default,
                                alter column joined_at drop default
                            """);
                } catch (RuntimeException | SQLException failure) {
                    statement.execute("drop schema " + schema + " cascade");
                    throw failure;
                }
                return fixture;
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not prepare the PostgreSQL message test schema.", failure);
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
