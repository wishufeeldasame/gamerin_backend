package com.gamerin.backend.domain.bookmark.integration;

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
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.dto.request.CreateBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkCollectionResponse;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkMembershipResponse;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionItemRepository;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionRepository;
import com.gamerin.backend.domain.bookmark.service.BookmarkCollectionService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostBookmarkCommandService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "BOOKMARK_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookmarkCollectionPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 6;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(15);
    private static PostgresSchema postgresSchema;

    @Autowired
    private BookmarkCollectionService collectionService;
    @Autowired
    private PostBookmarkCommandService bookmarkCommandService;
    @Autowired
    private BookmarkCollectionRepository collectionRepository;
    @Autowired
    private BookmarkCollectionItemRepository itemRepository;
    @Autowired
    private PostBookmarkRepository postBookmarkRepository;
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
    void concurrentAddsCreateOneBookmarkAndOneMembership() throws Exception {
        CollectionFixture fixture = createCollectionFixture("same_add");
        List<Supplier<BookmarkMembershipResponse>> operations = IntStream
                .range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<BookmarkMembershipResponse>) () -> collectionService.addPost(
                        fixture.principal(),
                        fixture.collectionId(),
                        fixture.postId()
                ))
                .toList();

        List<Outcome<BookmarkMembershipResponse>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        for (Outcome<BookmarkMembershipResponse> outcome : outcomes) {
            assertThat(outcome.value().bookmarkedByMe()).isTrue();
            assertThat(outcome.value().collectionIds()).containsExactly(fixture.collectionId());
        }
        assertThat(postBookmarkRepository.existsByPostIdAndUserId(fixture.postId(), fixture.userId())).isTrue();
        assertThat(itemRepository.findCollectionIdsContainingPost(fixture.userId(), fixture.postId()))
                .containsExactly(fixture.collectionId());
        assertThat(countCanonicalBookmarks(fixture)).isEqualTo(1);
        assertThat(countCollectionItems(fixture.collectionId())).isEqualTo(1);
    }

    @Test
    void concurrentRemovesAreIdempotentAndKeepTheCanonicalBookmark() throws Exception {
        CollectionFixture fixture = createCollectionFixture("same_remove");
        collectionService.addPost(fixture.principal(), fixture.collectionId(), fixture.postId());
        List<Supplier<BookmarkMembershipResponse>> operations = IntStream
                .range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<BookmarkMembershipResponse>) () -> collectionService.removePost(
                        fixture.principal(),
                        fixture.collectionId(),
                        fixture.postId()
                ))
                .toList();

        List<Outcome<BookmarkMembershipResponse>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        for (Outcome<BookmarkMembershipResponse> outcome : outcomes) {
            assertThat(outcome.value().bookmarkedByMe()).isTrue();
            assertThat(outcome.value().collectionIds()).isEmpty();
        }
        assertThat(postBookmarkRepository.existsByPostIdAndUserId(fixture.postId(), fixture.userId())).isTrue();
        assertThat(itemRepository.findCollectionIdsContainingPost(fixture.userId(), fixture.postId())).isEmpty();
        assertThat(countCanonicalBookmarks(fixture)).isEqualTo(1);
        assertThat(countCollectionItems(fixture.collectionId())).isZero();
    }

    @RepeatedTest(5)
    void concurrentAddAndRemoveFinishWithoutDuplicatesOrBrokenReferences() throws Exception {
        CollectionFixture fixture = createCollectionFixture("add_remove");
        List<Supplier<BookmarkMembershipResponse>> operations = List.of(
                () -> collectionService.addPost(
                        fixture.principal(),
                        fixture.collectionId(),
                        fixture.postId()
                ),
                () -> collectionService.removePost(
                        fixture.principal(),
                        fixture.collectionId(),
                        fixture.postId()
                )
        );

        List<Outcome<BookmarkMembershipResponse>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        assertThat(postBookmarkRepository.existsByPostIdAndUserId(fixture.postId(), fixture.userId())).isTrue();
        assertThat(itemRepository.findCollectionIdsContainingPost(fixture.userId(), fixture.postId()))
                .allMatch(fixture.collectionId()::equals)
                .hasSizeLessThanOrEqualTo(1);
        assertThat(countCanonicalBookmarks(fixture)).isEqualTo(1);
        assertThat(countCollectionItems(fixture.collectionId())).isBetween(0L, 1L);
        assertThat(countOrphanCollectionItems()).isZero();
    }

    @Test
    void concurrentDuplicateCollectionCreationReturnsConflictsWithoutDuplicateRows() throws Exception {
        UserPostFixture fixture = createUserPostFixture("same_name");
        List<Supplier<BookmarkCollectionResponse>> operations = IntStream
                .range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<BookmarkCollectionResponse>) () -> collectionService.create(
                        fixture.principal(),
                        new CreateBookmarkCollectionRequest("Ranked", null)
                ))
                .toList();

        List<Outcome<BookmarkCollectionResponse>> outcomes = runConcurrently(operations);

        long successes = outcomes.stream().filter(Outcome::succeeded).count();
        long conflicts = outcomes.stream()
                .map(Outcome::error)
                .filter(ResponseStatusException.class::isInstance)
                .map(ResponseStatusException.class::cast)
                .filter(error -> error.getStatusCode().value() == HttpStatus.CONFLICT.value())
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(CONCURRENT_REQUESTS - 1L);
        assertThat(collectionRepository.countByUserId(fixture.userId())).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentGlobalUnbookmarkAndCollectionAddNeverLeaveAnOrphanMembership() throws Exception {
        CollectionFixture fixture = createCollectionFixture("global_race");
        List<Supplier<Object>> operations = List.of(
                () -> collectionService.addPost(
                        fixture.principal(),
                        fixture.collectionId(),
                        fixture.postId()
                ),
                () -> {
                    bookmarkCommandService.unbookmark(fixture.principal(), fixture.postId());
                    return null;
                }
        );

        List<Outcome<Object>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes);
        boolean canonicalBookmarkExists = postBookmarkRepository.existsByPostIdAndUserId(
                fixture.postId(),
                fixture.userId()
        );
        List<UUID> collectionIds = itemRepository.findCollectionIdsContainingPost(
                fixture.userId(),
                fixture.postId()
        );
        assertThat(collectionIds).hasSizeLessThanOrEqualTo(1);
        assertThat(collectionIds.isEmpty() || canonicalBookmarkExists).isTrue();
        assertThat(countCollectionItems(fixture.collectionId())).isBetween(0L, 1L);
        assertThat(countOrphanCollectionItems()).isZero();
    }

    private CollectionFixture createCollectionFixture(String prefix) {
        UserPostFixture fixture = createUserPostFixture(prefix);
        UUID collectionId = collectionService.create(
                fixture.principal(),
                new CreateBookmarkCollectionRequest(
                        "Collection-" + UUID.randomUUID().toString().substring(0, 8),
                        null
                )
        ).collectionId();
        return new CollectionFixture(
                fixture.principal(),
                fixture.userId(),
                fixture.postId(),
                collectionId
        );
    }

    private UserPostFixture createUserPostFixture(String prefix) {
        return transactionTemplate.execute(status -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            User user = User.createLocal(
                    prefix + "+" + suffix + "@example.com",
                    prefix + suffix,
                    prefix,
                    "encoded-password"
            );
            user.setProfile(UserProfile.createDefault(user));
            User savedUser = userRepository.saveAndFlush(user);
            Post post = postRepository.saveAndFlush(Post.create(savedUser, "concurrency post"));
            return new UserPostFixture(
                    CustomUserPrincipal.from(savedUser),
                    savedUser.getId(),
                    post.getId()
            );
        });
    }

    private <T> List<Outcome<T>> runConcurrently(List<Supplier<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "bookmark-postgres-" + threadNumber.incrementAndGet());
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
                    .as("Concurrent database operation must complete without an exception")
                    .isNull();
        }
    }

    private long countCanonicalBookmarks(CollectionFixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from post_bookmarks where post_id = ? and user_id = ?",
                Long.class,
                fixture.postId(),
                fixture.userId()
        );
        return count == null ? 0L : count;
    }

    private long countCollectionItems(UUID collectionId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from bookmark_collection_items where collection_id = ?",
                Long.class,
                collectionId
        );
        return count == null ? 0L : count;
    }

    private long countOrphanCollectionItems() {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from bookmark_collection_items item
                left join post_bookmarks bookmark on bookmark.id = item.post_bookmark_id
                where bookmark.id is null
                """,
                Long.class
        );
        return count == null ? 0L : count;
    }

    private record UserPostFixture(CustomUserPrincipal principal, UUID userId, UUID postId) {
    }

    private record CollectionFixture(
            CustomUserPrincipal principal,
            UUID userId,
            UUID postId,
            UUID collectionId
    ) {
    }

    private record Outcome<T>(T value, Throwable error) {
        private static <T> Outcome<T> success(T value) {
            return new Outcome<>(value, null);
        }

        private static <T> Outcome<T> failure(Throwable error) {
            return new Outcome<>(null, error);
        }

        private boolean succeeded() {
            return error == null;
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("BOOKMARK_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("BOOKMARK_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("BOOKMARK_POSTGRES_TEST_PASSWORD", true);
            String schema = "bookmark_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL concurrency test.");
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
                throw new IllegalStateException("Could not prepare the PostgreSQL test schema.", error);
            }
        }
    }
}
