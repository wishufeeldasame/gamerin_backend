package com.gamerin.backend.domain.message.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.DirectMessageAttachment;
import com.gamerin.backend.domain.message.entity.MessageAttachmentType;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManager;

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
    @Autowired
    private DirectMessageAttachmentRepository directMessageAttachmentRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DataSource dataSource;
    @MockitoSpyBean
    private ContentModerationService contentModerationService;

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

    @Test
    void attachmentAccessRejectsMessagesSentBeforeParticipantClearedAt() {
        User sender = saveUser("sender");
        User viewer = saveUser("viewer");
        var conversationResponse = messageService.createConversation(CustomUserPrincipal.from(sender),
                new CreateConversationRequest(null, viewer.getId()));
        MessageConversation conversation = entityManager.find(MessageConversation.class, conversationResponse.id());
        OffsetDateTime clearedAt = OffsetDateTime.parse("2026-10-20T10:00:00+09:00");

        UUID beforeClear = saveAttachment(conversation, sender, clearedAt.minusSeconds(1));
        UUID atClear = saveAttachment(conversation, sender, clearedAt);
        UUID afterClear = saveAttachment(conversation, sender, clearedAt.plusSeconds(1));
        jdbcTemplate.update("""
                update message_participants set cleared_at = ?, deleted_at = null
                where conversation_id = ? and user_id = ?
                """, clearedAt, conversation.getId(), viewer.getId());
        entityManager.clear();

        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(beforeClear, viewer.getId())).isEmpty();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(atClear, viewer.getId())).isEmpty();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(afterClear, viewer.getId())).isPresent();
        assertThat(directMessageAttachmentRepository.findAccessibleActiveById(beforeClear, sender.getId())).isPresent();
    }

    /**
     * #130: 외부 검열이 실행되는 동안 풀의 활성 커넥션이 0이어야 한다(검열을 트랜잭션 밖에서 수행).
     * 클래스 트랜잭션이 커넥션을 잡지 않도록 테스트 트랜잭션을 중단하고, 커밋된 데이터는 직접 정리한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sendMessageDoesNotHoldConnectionDuringModeration() throws Exception {
        try {
            User sender = saveUser("sender");
            User recipient = saveUser("recipient");
            CustomUserPrincipal senderPrincipal = CustomUserPrincipal.from(sender);
            UUID conversationId = messageService.createConversation(senderPrincipal,
                    new CreateConversationRequest(null, recipient.getId())).id();

            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            AtomicInteger activeDuringModeration = new AtomicInteger(-1);
            doAnswer(invocation -> {
                activeDuringModeration.set(hikari.getHikariPoolMXBean().getActiveConnections());
                return null;
            }).when(contentModerationService).assertTextAllowed(anyString());

            messageService.sendMessage(senderPrincipal, conversationId, new SendMessageRequest("검열 중 커넥션 확인", null));

            assertThat(activeDuringModeration.get()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from direct_messages where conversation_id = ?", Long.class, conversationId))
                    .isEqualTo(1L);
        } finally {
            jdbcTemplate.execute("truncate table notifications, direct_messages, message_participants, "
                    + "message_conversations, users cascade");
        }
    }

    private UUID saveAttachment(MessageConversation conversation, User sender, OffsetDateTime createdAt) {
        DirectMessage message = DirectMessage.create(conversation, sender, "attachment", null);
        entityManager.persist(message);
        DirectMessageAttachment attachment = DirectMessageAttachment.create(
                message, MessageAttachmentType.IMAGE, "image.jpg", "/uploads/message-attachments/image.jpg", 0);
        entityManager.persist(attachment);
        entityManager.flush();
        jdbcTemplate.update("update direct_messages set created_at = ? where id = ?", createdAt, message.getId());
        return attachment.getId();
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
