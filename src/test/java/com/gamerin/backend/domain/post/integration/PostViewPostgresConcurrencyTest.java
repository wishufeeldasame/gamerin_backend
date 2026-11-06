package com.gamerin.backend.domain.post.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.post.service.PostViewService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

/** #183: 조회수 증가·24시간 중복 제외가 PostgreSQL에서 동시 요청에도 정확한지 확인한다(Flyway V61 포함). */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostViewPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 16;
    private static PostgresSchema postgresSchema;

    @Autowired
    private PostViewService postViewService;
    @Autowired
    private PostService postService;
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
        registry.add("app.moderation.enabled", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
    }

    @Test
    void distinctUsersViewingAtOnceAreAllCountedWithoutLostUpdates() throws Exception {
        Post post = createPost();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            UUID userId = createUser().getId();
            tasks.add(() -> postViewService.record(userId, List.of(post.getId())));
        }

        assertThat(runConcurrently(tasks)).containsOnly(1);
        assertThat(viewCount(post)).isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void sameUserViewingAtOnceIsCountedExactlyOnce() throws Exception {
        Post post = createPost();
        UUID userId = createUser().getId();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> postViewService.record(userId, List.of(post.getId())));
        }

        assertThat(runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(viewCount(post)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from post_views where post_id = ?", Long.class,
                post.getId())).isEqualTo(1);
    }

    @Test
    void expiredViewIsRecountedExactlyOnceUnderConcurrency() throws Exception {
        Post post = createPost();
        UUID userId = createUser().getId();
        postViewService.record(userId, List.of(post.getId()));
        jdbcTemplate.update("update post_views set viewed_at = viewed_at - interval '25 hours' where post_id = ?",
                post.getId());
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            tasks.add(() -> postViewService.record(userId, List.of(post.getId())));
        }

        assertThat(runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(viewCount(post)).isEqualTo(2);
    }

    @Test
    void overlappingBatchesInDifferentOrdersDoNotDeadlock() throws Exception {
        Post first = createPost();
        Post second = createPost();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            UUID userId = createUser().getId();
            List<UUID> ids = i % 2 == 0 ? List.of(first.getId(), second.getId())
                    : List.of(second.getId(), first.getId());
            tasks.add(() -> postViewService.record(userId, ids));
        }

        assertThat(runConcurrently(tasks)).containsOnly(2);
        assertThat(viewCount(first)).isEqualTo(CONCURRENT_REQUESTS);
        assertThat(viewCount(second)).isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void viewsAndLikesBySameUserDoNotDeadlock() throws Exception {
        Post post = createPost();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            User user = createUser();
            CustomUserPrincipal principal = CustomUserPrincipal.from(user);
            tasks.add(() -> postViewService.record(user.getId(), List.of(post.getId())));
            tasks.add(() -> {
                postService.like(principal, post.getId());
                return 0;
            });
        }

        runConcurrently(tasks); // 교착 피해자가 나오면 future.get이 예외로 실패한다
        assertThat(viewCount(post)).isEqualTo(CONCURRENT_REQUESTS);
    }

    private long viewCount(Post post) {
        return jdbcTemplate.queryForObject("select view_count from posts where id = ?", Long.class, post.getId());
    }

    private Post createPost() {
        User author = createUser();
        return transactionTemplate.execute(status -> postRepository.saveAndFlush(Post.create(author, "post")));
    }

    private User createUser() {
        String handle = "v" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return transactionTemplate.execute(status -> {
            User user = User.createLocal(handle + "@example.com", handle, handle, "encoded-password");
            user.setProfile(UserProfile.createDefault(user));
            return userRepository.saveAndFlush(user);
        });
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("HASHTAG_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("HASHTAG_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("HASHTAG_POSTGRES_TEST_PASSWORD", true);
            String schema = "post_view_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL post view test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL post view test schema.", error);
            }
        }
    }
}
