package com.gamerin.backend.domain.post.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.repository.UserMembershipRepository;
import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreatePostRequest;
import com.gamerin.backend.domain.post.dto.request.UpdatePostRequest;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.moderation.ContentModerationService;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import com.zaxxer.hikari.HikariDataSource;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostEditPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(15);
    private static PostgresSchema postgresSchema;

    @Autowired
    private PostService postService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserMembershipRepository userMembershipRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
        registry.add("app.moderation.enabled", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    /**
     * KP-C3 (#92): 동시에 여러 수정 요청이 들어와도 정확히 1회만 저장되고 edit_count = 1을 유지한다.
     */
    @Test
    void concurrentEditsAllowOnlyOneUpdateAndKeepEditCountAtOne() throws Exception {
        User author = createActiveMemberUser("editauthor");
        CustomUserPrincipal principal = CustomUserPrincipal.from(author);
        PostDetailResponse created = postService.create(principal, new CreatePostRequest("최초 게시물 본문"));
        UUID postId = created.postId();

        List<Supplier<PostDetailResponse>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<PostDetailResponse>) () -> postService.update(
                        principal,
                        postId,
                        new UpdatePostRequest("동시 수정 본문 #" + index)))
                .toList();

        List<Outcome<PostDetailResponse>> outcomes = runConcurrently(operations);

        long successCount = outcomes.stream().filter(outcome -> outcome.error() == null).count();
        long conflictCount = outcomes.stream()
                .map(Outcome::error)
                .filter(ResponseStatusException.class::isInstance)
                .map(ResponseStatusException.class::cast)
                .filter(ex -> ex.getStatusCode().value() == HttpStatus.CONFLICT.value())
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(CONCURRENT_REQUESTS - 1);

        Post updatedPost = postRepository.findById(postId).orElseThrow();
        assertThat(updatedPost.getEditCount()).isEqualTo(1);
        assertThat(updatedPost.getContent()).startsWith("동시 수정 본문 #");
    }

    /**
     * KP-C3 (#92): 수정 시 해시태그를 재파싱해 교체하고, 새로 추가된 멘션만 알림을 생성하며 제거된 멘션 알림은 유지한다.
     */
    @Test
    void updateResyncsHashtagsNotifiesOnlyNewMentionsAndPreservesRemovedMentionNotifications() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User author = createActiveMemberUser("author" + suffix);
        User removedUser = createUser("removed" + suffix, "Removed User");
        User keptUser = createUser("kept" + suffix, "Kept User");
        User addedUser = createUser("added" + suffix, "Added User");
        CustomUserPrincipal principal = CustomUserPrincipal.from(author);

        PostDetailResponse created = postService.create(
                principal,
                new CreatePostRequest("초기 본문 #oldtag @" + removedUser.getHandle() + " @" + keptUser.getHandle()));
        UUID postId = created.postId();

        PostDetailResponse updated = postService.update(
                principal,
                postId,
                new UpdatePostRequest("수정 본문 #newtag @" + keptUser.getHandle() + " @" + addedUser.getHandle()));

        assertThat(updated.content())
                .isEqualTo("수정 본문 #newtag @" + keptUser.getHandle() + " @" + addedUser.getHandle());
        assertThat(updated.editCount()).isEqualTo(1);
        assertThat(updated.editableUntil()).isNotNull();

        assertThat(count("""
                select count(*)
                from post_hashtags ph
                join hashtags h on h.id = ph.hashtag_id
                where ph.post_id = ? and h.normalized_name = 'oldtag'
                """, postId)).isZero();
        assertThat(count("""
                select count(*)
                from post_hashtags ph
                join hashtags h on h.id = ph.hashtag_id
                where ph.post_id = ? and h.normalized_name = 'newtag'
                """, postId)).isEqualTo(1);

        assertThat(count("""
                select count(*)
                from notifications
                where post_id = ? and recipient_id = ? and type = 'MENTION'
                """, postId, removedUser.getId())).isEqualTo(1);
        assertThat(count("""
                select count(*)
                from notifications
                where post_id = ? and recipient_id = ? and type = 'MENTION'
                """, postId, keptUser.getId())).isEqualTo(1);
        assertThat(count("""
                select count(*)
                from notifications
                where post_id = ? and recipient_id = ? and type = 'MENTION'
                """, postId, addedUser.getId())).isEqualTo(1);
    }

    /**
     * #130: 외부 검열이 실행되는 동안 풀의 활성 커넥션이 0이어야 한다(검열을 트랜잭션 밖에서 수행).
     */
    @Test
    void createCommentDoesNotHoldConnectionDuringModeration() throws Exception {
        User author = createActiveMemberUser("cmtauthor");
        CustomUserPrincipal principal = CustomUserPrincipal.from(author);
        UUID postId = postService.create(principal, new CreatePostRequest("댓글 대상 게시물")).postId();
        AtomicInteger activeDuringModeration = recordActiveConnectionsDuringModeration();

        postService.createComment(principal, postId, new CreateCommentRequest("검열 중 커넥션 확인"));

        assertThat(activeDuringModeration.get()).isZero();
        assertThat(count("select count(*) from post_comments where post_id = ?", postId)).isEqualTo(1);
    }

    /**
     * #130: 수정 요청도 검열 구간에는 커넥션을 잡지 않고, 검열 뒤 쓰기는 정상 반영된다.
     */
    @Test
    void updateDoesNotHoldConnectionDuringModeration() throws Exception {
        User author = createActiveMemberUser("updauthor");
        CustomUserPrincipal principal = CustomUserPrincipal.from(author);
        UUID postId = postService.create(principal, new CreatePostRequest("수정 전 본문")).postId();
        AtomicInteger activeDuringModeration = recordActiveConnectionsDuringModeration();

        postService.update(principal, postId, new UpdatePostRequest("수정 후 본문"));

        assertThat(activeDuringModeration.get()).isZero();
        Post updatedPost = postRepository.findById(postId).orElseThrow();
        assertThat(updatedPost.getContent()).isEqualTo("수정 후 본문");
        assertThat(updatedPost.getEditCount()).isEqualTo(1);
    }

    private AtomicInteger recordActiveConnectionsDuringModeration() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger observed = new AtomicInteger(-1);
        doAnswer(invocation -> {
            observed.set(hikari.getHikariPoolMXBean().getActiveConnections());
            return invocation.callRealMethod();
        }).when(contentModerationService).assertTextAllowed(anyString());
        return observed;
    }

    private User createActiveMemberUser(String handlePrefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return transactionTemplate.execute(status -> {
            User user = createUserInCurrentTransaction(handlePrefix + suffix, "Member " + suffix);
            userMembershipRepository.saveAndFlush(
                    UserMembership.create(user, OffsetDateTime.now().plusDays(30)));
            return user;
        });
    }

    private User createUser(String handle, String nickname) {
        return transactionTemplate.execute(status -> createUserInCurrentTransaction(handle, nickname));
    }

    private User createUserInCurrentTransaction(String handle, String nickname) {
        User user = User.createLocal(
                handle + "@example.com",
                handle,
                nickname,
                "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private <T> List<Outcome<T>> runConcurrently(List<Supplier<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "post-edit-postgres-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Supplier<T> operation : operations) {
                futures.add(executor.submit(concurrentTask(operation, ready, start)));
            }
            assertThat(ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private <T> Callable<Outcome<T>> concurrentTask(
            Supplier<T> operation,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            try {
                if (!start.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return Outcome.failure(new IllegalStateException("Concurrent start timed out."));
                }
                T value = transactionTemplate.execute(status -> {
                    jdbcTemplate.execute("set local lock_timeout = '5s'");
                    jdbcTemplate.execute("set local statement_timeout = '10s'");
                    return operation.get();
                });
                return Outcome.success(value);
            } catch (Throwable error) {
                return Outcome.failure(error);
            }
        };
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private record Outcome<T>(T value, Throwable error) {
        private static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failure(Throwable error) {
            return new Outcome<>(null, error);
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("HASHTAG_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("HASHTAG_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("HASHTAG_POSTGRES_TEST_PASSWORD", true);
            String schema = "post_edit_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL post edit test.");
            }
            return value;
        }

        private static void executeSchemaStatement(
                String url,
                String username,
                String password,
                String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL post edit test schema.", error);
            }
        }
    }
}