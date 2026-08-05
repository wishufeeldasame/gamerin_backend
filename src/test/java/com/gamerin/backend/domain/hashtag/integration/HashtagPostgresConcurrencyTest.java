package com.gamerin.backend.domain.hashtag.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.hashtag.model.HashtagSummary;
import com.gamerin.backend.domain.hashtag.repository.HashtagQueryRepository;
import com.gamerin.backend.domain.hashtag.service.HashtagService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.search.repository.SearchQueryRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HashtagPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 8;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(15);
    private static PostgresSchema postgresSchema;

    @Autowired
    private HashtagService hashtagService;
    @Autowired
    private HashtagQueryRepository hashtagQueryRepository;
    @Autowired
    private SearchQueryRepository searchQueryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
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
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    void concurrentAttachmentKeepsCaseDistinctHashtagsAndOneRelationEach() throws Exception {
        UUID postId = createPost("동시 저장 #PUBG #pubg");
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<Void>) () -> {
                    attachInCurrentTransaction(postId);
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        assertThat(count("select count(*) from hashtags where normalized_name in ('PUBG', 'pubg')"))
                .isEqualTo(2);
        assertThat(count("""
                select count(*)
                from post_hashtags ph
                join hashtags h on h.id = ph.hashtag_id
                where ph.post_id = ? and h.normalized_name in ('PUBG', 'pubg')
                """, postId)).isEqualTo(2);
    }

    @Test
    void concurrentPostsShareOneHashtagWithoutLosingRelations() throws Exception {
        UUID firstPostId = createPost("첫 글 #ranked");
        UUID secondPostId = createPost("둘째 글 #ranked");
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Void>) () -> {
                    attachInCurrentTransaction(index % 2 == 0 ? firstPostId : secondPostId);
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        assertThat(count("select count(*) from hashtags where normalized_name = 'ranked'"))
                .isEqualTo(1);
        assertThat(count("""
                select count(*)
                from post_hashtags ph
                join hashtags h on h.id = ph.hashtag_id
                where h.normalized_name = 'ranked'
                """)).isEqualTo(2);
    }

    @Test
    void postgresQueriesExcludeSoftDeletedPostsAndCountActiveMatches() {
        UUID firstPostId = createPost("활성 글 #searchable");
        UUID secondPostId = createPost("삭제 글 #searchable");
        transactionTemplate.executeWithoutResult(status -> {
            attachInCurrentTransaction(firstPostId);
            Post deleted = postRepository.findById(secondPostId).orElseThrow();
            hashtagService.attachToPost(deleted);
            deleted.softDelete();
        });

        transactionTemplate.executeWithoutResult(status -> {
            List<Post> posts = hashtagQueryRepository.findActivePosts("searchable", null, 10);
            List<HashtagSummary> suggestions = hashtagQueryRepository.findActiveSuggestions("search", 10);

            assertThat(posts).extracting(Post::getId).containsExactly(firstPostId);
            assertThat(suggestions).hasSize(1);
            assertThat(suggestions.getFirst().postCount()).isEqualTo(1);
        });
    }

    @Test
    void postgresGlobalSearchMatchesAccountsAndPostsCaseInsensitively() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String handle = "pgsearch" + suffix;
        UUID userId = createUser(handle, "Postgres Search User");
        UUID postId = transactionTemplate.execute(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            return postRepository.saveAndFlush(
                    Post.create(user, "POSTGRESNEEDLE 경기 기록")
            ).getId();
        });

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(searchQueryRepository.findActiveAccounts("pgsearch" + suffix, null, 10))
                    .extracting(User::getId)
                    .containsExactly(userId);
            assertThat(searchQueryRepository.findActivePosts("postgresneedle", null, 10))
                    .extracting(Post::getId)
                    .containsExactly(postId);
        });
    }

    private UUID createPost(String content) {
        return transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            UUID userId = createUserInCurrentTransaction("user" + suffix, "User " + suffix);
            User savedUser = userRepository.findById(userId).orElseThrow();
            return postRepository.saveAndFlush(Post.create(savedUser, content)).getId();
        });
    }

    private UUID createUser(String handle, String nickname) {
        return transactionTemplate.execute(status -> createUserInCurrentTransaction(handle, nickname));
    }

    private UUID createUserInCurrentTransaction(String handle, String nickname) {
        User user = User.createLocal(
                handle + "@example.com",
                handle,
                nickname,
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user).getId();
    }

    private void attachInCurrentTransaction(UUID postId) {
        Post post = postRepository.findById(postId).orElseThrow();
        hashtagService.attachToPost(post);
    }

    private <T> List<Outcome<T>> runConcurrently(List<Supplier<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "hashtag-postgres-" + threadNumber.incrementAndGet());
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
            CountDownLatch start
    ) {
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

    private void assertNoFailures(List<? extends Outcome<?>> outcomes) {
        for (Outcome<?> outcome : outcomes) {
            assertThat(outcome.error())
                    .as("Concurrent hashtag operation must complete without an exception")
                    .isNull();
        }
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
            String schema = "hashtag_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL hashtag test.");
            }
            return value;
        }

        private static void executeSchemaStatement(
                String url,
                String username,
                String password,
                String sql
        ) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL hashtag test schema.", error);
            }
        }
    }
}
