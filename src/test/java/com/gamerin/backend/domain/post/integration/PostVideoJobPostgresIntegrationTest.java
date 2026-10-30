package com.gamerin.backend.domain.post.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.post.entity.PostVideoJob;
import com.gamerin.backend.domain.post.entity.PostVideoJobStatus;
import com.gamerin.backend.domain.post.entity.VideoQualityTier;
import com.gamerin.backend.domain.post.repository.PostVideoJobRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

/**
 * V45 마이그레이션이 엔티티와 맞는지(ddl validate)와, 대기 작업 조회가 잠긴 행을 건너뛰는지(SKIP LOCKED) 확인한다.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostVideoJobPostgresIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private PostVideoJobRepository postVideoJobRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

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
        registry.add("app.moderation.enabled", () -> "false");
        registry.add("app.media.video.processing.worker-enabled", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    void oldestQueuedJobLookupSkipsJobLockedByAnotherWorker() throws Exception {
        User user = createUser();
        OffsetDateTime now = OffsetDateTime.now();
        PostVideoJob first = postVideoJobRepository.saveAndFlush(job(user, now.minusSeconds(10)));
        PostVideoJob second = postVideoJobRepository.saveAndFlush(job(user, now));

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<UUID> lockingWorker = CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status -> {
            UUID lockedId = oldestQueuedId();
            firstLocked.countDown();
            await(releaseFirst);
            return lockedId;
        }));

        assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
        UUID otherWorkerPick = transactionTemplate.execute(status -> oldestQueuedId());
        releaseFirst.countDown();

        assertThat(lockingWorker.get(5, TimeUnit.SECONDS)).isEqualTo(first.getId());
        assertThat(otherWorkerPick).isEqualTo(second.getId());
    }

    private UUID oldestQueuedId() {
        List<PostVideoJob> jobs = postVideoJobRepository.findOldestForUpdate(PostVideoJobStatus.QUEUED, PageRequest.of(0, 1));
        return jobs.isEmpty() ? null : jobs.getFirst().getId();
    }

    private PostVideoJob job(User user, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        return PostVideoJob.queue(id, user.getId(), "clip", VideoQualityTier.HD, "/tmp/" + id + ".mp4", ".mp4",
                null, createdAt);
    }

    private User createUser() {
        String handle = "video" + UUID.randomUUID().toString().substring(0, 8);
        User user = User.createLocal(handle + "@example.com", handle, "Video", "hash");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("HASHTAG_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("HASHTAG_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("HASHTAG_POSTGRES_TEST_PASSWORD", true);
            String schema = "post_video_jobs_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL video job test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL video job test schema.", error);
            }
        }
    }
}
