package com.gamerin.backend.domain.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.service.FollowService;
import com.gamerin.backend.domain.notification.service.NotificationQueryService;
import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.ShareTarget;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostCleanupService;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "NOTIFICATION_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 6;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(15);
    private static PostgresSchema postgresSchema;

    @Autowired
    private PostService postService;
    @Autowired
    private FollowService followService;
    @Autowired
    private NotificationQueryService notificationQueryService;
    @Autowired
    private PostCleanupService postCleanupService;
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
    void concurrentDuplicateLikesKeepOneLikeAndOneNotification() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<Void>) () -> {
                    postService.like(fixture.actorPrincipal(), fixture.postId());
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "like");
        assertThat(count("select count(*) from post_likes where post_id = ?", fixture.postId()))
                .isEqualTo(1L);
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId()))
                .isEqualTo(1L);
        assertThat(count("select like_count from posts where id = ?", fixture.postId()))
                .isEqualTo(1L);
    }

    @Test
    void concurrentDuplicateFollowsKeepOneFollowAndOneNotification() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<Void>) () -> {
                    followService.follow(fixture.actorPrincipal(), fixture.recipientHandle());
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "follow");
        assertThat(count("""
                select count(*)
                from follows
                where follower_id = ? and followee_id = ?
                """, fixture.actorId(), fixture.recipientId())).isEqualTo(1L);
        assertThat(count("""
                select count(*)
                from notifications
                where actor_id = ? and recipient_id = ? and type = 'FOLLOW'
                """, fixture.actorId(), fixture.recipientId())).isEqualTo(1L);
    }

    @Test
    void concurrentLikesFromDifferentActorsKeepAllSourcesCountersAndNotifications() throws Exception {
        MultiActorFixture fixture = createMultiActorFixture(CONCURRENT_REQUESTS);
        List<Supplier<Void>> operations = fixture.actors().stream()
                .map(actor -> (Supplier<Void>) () -> {
                    postService.like(actor.principal(), fixture.postId());
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "likes from different actors");
        assertThat(count("select count(*) from post_likes where post_id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count("select like_count from posts where id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void concurrentLikesAndCommentsKeepIndependentPostCountersAccurate() throws Exception {
        MultiActorFixture fixture = createMultiActorFixture(CONCURRENT_REQUESTS);
        List<Supplier<Void>> operations = new ArrayList<>();
        for (int index = 0; index < fixture.actors().size(); index++) {
            Actor actor = fixture.actors().get(index);
            if (index % 2 == 0) {
                operations.add(() -> {
                    postService.like(actor.principal(), fixture.postId());
                    return null;
                });
            } else {
                int commentNumber = index;
                operations.add(() -> {
                    postService.createComment(
                            actor.principal(),
                            fixture.postId(),
                            new CreateCommentRequest("concurrent comment " + commentNumber)
                    );
                    return null;
                });
            }
        }

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        long expectedLikes = (CONCURRENT_REQUESTS + 1L) / 2L;
        long expectedComments = CONCURRENT_REQUESTS / 2L;
        assertNoFailures(outcomes, "mixed like and comment");
        assertThat(count("select count(*) from post_likes where post_id = ?", fixture.postId()))
                .isEqualTo(expectedLikes);
        assertThat(count("select count(*) from post_comments where post_id = ?", fixture.postId()))
                .isEqualTo(expectedComments);
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count("select like_count from posts where id = ?", fixture.postId()))
                .isEqualTo(expectedLikes);
        assertThat(count("select comment_count from posts where id = ?", fixture.postId()))
                .isEqualTo(expectedComments);
    }

    @Test
    void concurrentLikeAndUnlikeKeepSourceCounterAndNotificationConsistent() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Void>) () -> {
                    if (index % 2 == 0) {
                        postService.like(fixture.actorPrincipal(), fixture.postId());
                    } else {
                        postService.unlike(fixture.actorPrincipal(), fixture.postId());
                    }
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "like and unlike");
        long sourceCount = count("select count(*) from post_likes where post_id = ?", fixture.postId());
        long notificationCount = count(
                "select count(*) from notifications where post_id = ? and type = 'LIKE'",
                fixture.postId()
        );
        long denormalizedCount = count("select like_count from posts where id = ?", fixture.postId());
        assertThat(sourceCount).isBetween(0L, 1L);
        assertThat(notificationCount).isEqualTo(sourceCount);
        assertThat(denormalizedCount).isEqualTo(sourceCount);
    }

    @Test
    void concurrentIndividualAndReadAllRequestsLeaveEveryExistingNotificationRead() throws Exception {
        MultiActorFixture fixture = createMultiActorFixture(CONCURRENT_REQUESTS);
        for (Actor actor : fixture.actors()) {
            postService.like(actor.principal(), fixture.postId());
        }
        List<UUID> notificationIds = jdbcTemplate.queryForList(
                "select id from notifications where recipient_id = ? order by created_at, id",
                UUID.class,
                fixture.recipientId()
        );
        List<Supplier<Void>> operations = new ArrayList<>();
        operations.add(() -> {
            notificationQueryService.markAllRead(fixture.recipientPrincipal());
            return null;
        });
        for (UUID notificationId : notificationIds) {
            operations.add(() -> {
                notificationQueryService.markRead(fixture.recipientPrincipal(), notificationId);
                return null;
            });
        }

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "individual and read-all");
        assertThat(count(
                "select count(*) from notifications where recipient_id = ? and read_at is null",
                fixture.recipientId()
        )).isZero();
    }

    @Test
    void cancelAndRecreateProduceOneFreshUnreadNotification() {
        Fixture fixture = createFixture();

        postService.like(fixture.actorPrincipal(), fixture.postId());
        UUID firstNotificationId = singleUuid(
                "select id from notifications where post_id = ? and actor_id = ?",
                fixture.postId(),
                fixture.actorId()
        );
        notificationQueryService.markRead(fixture.recipientPrincipal(), firstNotificationId);

        postService.unlike(fixture.actorPrincipal(), fixture.postId());
        postService.like(fixture.actorPrincipal(), fixture.postId());

        UUID recreatedNotificationId = singleUuid(
                "select id from notifications where post_id = ? and actor_id = ?",
                fixture.postId(),
                fixture.actorId()
        );
        assertThat(recreatedNotificationId).isNotEqualTo(firstNotificationId);
        assertThat(count(
                "select count(*) from notifications where post_id = ? and actor_id = ?",
                fixture.postId(),
                fixture.actorId()
        )).isEqualTo(1L);
        assertThat(count(
                "select count(*) from notifications where id = ? and read_at is null",
                recreatedNotificationId
        )).isEqualTo(1L);
    }

    @Test
    void hardDeletingPostCascadesSourcesAndNotifications() {
        Fixture fixture = createFixture();
        postService.like(fixture.actorPrincipal(), fixture.postId());
        jdbcTemplate.update(
                "update posts set deleted_at = current_timestamp - interval '2 days' where id = ?",
                fixture.postId()
        );

        postCleanupService.purgeExpiredSoftDeletedPosts();

        assertThat(count("select count(*) from posts where id = ?", fixture.postId())).isZero();
        assertThat(count("select count(*) from post_likes where post_id = ?", fixture.postId())).isZero();
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId())).isZero();
    }

    @Test
    void hardDeletingActorCascadesSourcesAndNotificationsWithoutAffectingRecipient() {
        Fixture fixture = createFixture();
        postService.like(fixture.actorPrincipal(), fixture.postId());

        jdbcTemplate.update("delete from users where id = ?", fixture.actorId());

        assertThat(count("select count(*) from users where id = ?", fixture.recipientId())).isEqualTo(1L);
        assertThat(count("select count(*) from post_likes where user_id = ?", fixture.actorId())).isZero();
        assertThat(count("select count(*) from notifications where actor_id = ?", fixture.actorId())).isZero();
    }

    @Test
    void concurrentSharesFromDifferentActorsKeepEveryEventAndCounter() throws Exception {
        MultiActorFixture fixture = createMultiActorFixture(CONCURRENT_REQUESTS);
        List<Supplier<Void>> operations = fixture.actors().stream()
                .map(actor -> (Supplier<Void>) () -> {
                    postService.share(
                            actor.principal(),
                            fixture.postId(),
                            new CreateShareRequest(ShareTarget.COPY_LINK)
                    );
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "shares from different actors");
        assertThat(count("select count(*) from post_shares where post_id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count("select share_count from posts where id = ?", fixture.postId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId())).isZero();
    }

    @Test
    void concurrentFollowsFromDifferentActorsCreateOneSourceAndNotificationPerActor() throws Exception {
        MultiActorFixture fixture = createMultiActorFixture(CONCURRENT_REQUESTS);
        List<Supplier<Void>> operations = fixture.actors().stream()
                .map(actor -> (Supplier<Void>) () -> {
                    followService.follow(actor.principal(), fixture.recipientHandle());
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "follows from different actors");
        assertThat(count("select count(*) from follows where followee_id = ?", fixture.recipientId()))
                .isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count(
                "select count(*) from notifications where recipient_id = ? and type = 'FOLLOW'",
                fixture.recipientId()
        )).isEqualTo(CONCURRENT_REQUESTS);
    }

    @Test
    void concurrentFollowAndUnfollowKeepSourceAndNotificationConsistent() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Void>) () -> {
                    if (index % 2 == 0) {
                        followService.follow(fixture.actorPrincipal(), fixture.recipientHandle());
                    } else {
                        followService.unfollow(fixture.actorPrincipal(), fixture.recipientHandle());
                    }
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "follow and unfollow");
        long sourceCount = count(
                "select count(*) from follows where follower_id = ? and followee_id = ?",
                fixture.actorId(),
                fixture.recipientId()
        );
        long notificationCount = count(
                "select count(*) from notifications where actor_id = ? and recipient_id = ? and type = 'FOLLOW'",
                fixture.actorId(),
                fixture.recipientId()
        );
        assertThat(sourceCount).isBetween(0L, 1L);
        assertThat(notificationCount).isEqualTo(sourceCount);
    }

    @Test
    void concurrentDeletesOfOneCommentDoNotUnderflowCounterOrLeaveNotification() throws Exception {
        Fixture fixture = createFixture();
        UUID commentId = postService.createComment(
                fixture.actorPrincipal(),
                fixture.postId(),
                new CreateCommentRequest("delete once")
        ).commentId();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<Void>) () -> {
                    postService.deleteComment(fixture.actorPrincipal(), fixture.postId(), commentId);
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertOneSuccessAndOnlyNotFoundFailures(outcomes, "delete the same comment");
        assertThat(count("select count(*) from post_comments where id = ?", commentId)).isZero();
        assertThat(count("select count(*) from notifications where comment_id = ?", commentId)).isZero();
        assertThat(count("select comment_count from posts where id = ?", fixture.postId())).isZero();
    }

    @Test
    void equalTimestampNotificationsUseUuidTieBreakerWithoutDuplicates() {
        MultiActorFixture fixture = createMultiActorFixture(3);
        for (Actor actor : fixture.actors()) {
            postService.like(actor.principal(), fixture.postId());
        }
        jdbcTemplate.update(
                "update notifications set created_at = timestamp with time zone '2026-08-12 10:00:00+09' "
                        + "where recipient_id = ?",
                fixture.recipientId()
        );
        List<UUID> expectedOrder = jdbcTemplate.queryForList(
                "select id from notifications where recipient_id = ? order by created_at desc, id desc",
                UUID.class,
                fixture.recipientId()
        );

        var firstPage = notificationQueryService.getNotifications(fixture.recipientPrincipal(), null, 2);
        var secondPage = notificationQueryService.getNotifications(
                fixture.recipientPrincipal(),
                firstPage.nextCursor(),
                2
        );
        List<UUID> actualOrder = new ArrayList<>();
        firstPage.items().forEach(item -> actualOrder.add(item.notificationId()));
        secondPage.items().forEach(item -> actualOrder.add(item.notificationId()));

        assertThat(firstPage.hasNext()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(actualOrder).containsExactlyElementsOf(expectedOrder);
        assertThat(actualOrder).doesNotHaveDuplicates();
    }

    @Test
    void databaseConstraintsRejectSelfMalformedAndDuplicateSourceNotifications() {
        Fixture fixture = createFixture();
        postService.like(fixture.actorPrincipal(), fixture.postId());
        UUID postLikeId = singleUuid(
                "select id from post_likes where post_id = ? and user_id = ?",
                fixture.postId(),
                fixture.actorId()
        );

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notifications(recipient_id, actor_id, type, post_id, post_like_id)
                values (?, ?, 'LIKE', ?, ?)
                """, fixture.recipientId(), fixture.actorId(), fixture.postId(), postLikeId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("delete from notifications where post_like_id = ?", postLikeId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notifications(recipient_id, actor_id, type, post_id, post_like_id)
                values (?, ?, 'LIKE', ?, ?)
                """, fixture.actorId(), fixture.actorId(), fixture.postId(), postLikeId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into notifications(recipient_id, actor_id, type, post_id)
                values (?, ?, 'LIKE', ?)
                """, fixture.recipientId(), fixture.actorId(), fixture.postId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentLikeAndPostDeleteHaveOnlyPolicyAllowedOutcomesAndStayConsistent() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = List.of(
                () -> {
                    postService.like(fixture.actorPrincipal(), fixture.postId());
                    return null;
                },
                () -> {
                    postService.delete(fixture.recipientPrincipal(), fixture.postId());
                    return null;
                }
        );

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertSuccessOrPostNotFound(outcomes.get(0), "like racing with post deletion");
        assertThat(outcomes.get(1).error()).as("Post deletion must succeed").isNull();
        assertThat(count("select count(*) from posts where id = ? and deleted_at is not null", fixture.postId()))
                .isEqualTo(1L);
        long sourceCount = count("select count(*) from post_likes where post_id = ?", fixture.postId());
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId()))
                .isEqualTo(sourceCount);
        assertThat(count("select like_count from posts where id = ?", fixture.postId()))
                .isEqualTo(sourceCount);
    }

    @Test
    void concurrentCommentAndPostDeleteHaveOnlyPolicyAllowedOutcomesAndStayConsistent() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = List.of(
                () -> {
                    postService.createComment(
                            fixture.actorPrincipal(),
                            fixture.postId(),
                            new CreateCommentRequest("racing comment")
                    );
                    return null;
                },
                () -> {
                    postService.delete(fixture.recipientPrincipal(), fixture.postId());
                    return null;
                }
        );

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertSuccessOrPostNotFound(outcomes.get(0), "comment racing with post deletion");
        assertThat(outcomes.get(1).error()).as("Post deletion must succeed").isNull();
        assertThat(count("select count(*) from posts where id = ? and deleted_at is not null", fixture.postId()))
                .isEqualTo(1L);
        long sourceCount = count("select count(*) from post_comments where post_id = ?", fixture.postId());
        assertThat(count("select count(*) from notifications where post_id = ?", fixture.postId()))
                .isEqualTo(sourceCount);
        assertThat(count("select comment_count from posts where id = ?", fixture.postId()))
                .isEqualTo(sourceCount);
    }

    private Fixture createFixture() {
        return transactionTemplate.execute(status -> {
            User recipient = saveUser("recipient");
            User actor = saveUser("actor");
            Post post = postRepository.saveAndFlush(Post.create(recipient, "target post"));
            return new Fixture(
                    recipient.getId(),
                    recipient.getHandle(),
                    CustomUserPrincipal.from(recipient),
                    actor.getId(),
                    CustomUserPrincipal.from(actor),
                    post.getId()
            );
        });
    }

    private MultiActorFixture createMultiActorFixture(int actorCount) {
        return transactionTemplate.execute(status -> {
            User recipient = saveUser("recipient");
            List<Actor> actors = IntStream.range(0, actorCount)
                    .mapToObj(index -> saveUser("actor" + index))
                    .map(actor -> new Actor(actor.getId(), CustomUserPrincipal.from(actor)))
                    .toList();
            Post post = postRepository.saveAndFlush(Post.create(recipient, "target post"));
            return new MultiActorFixture(
                    recipient.getId(),
                    recipient.getHandle(),
                    CustomUserPrincipal.from(recipient),
                    post.getId(),
                    actors
            );
        });
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(
                prefix + "+" + suffix + "@example.com",
                prefix + suffix,
                prefix,
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private <T> List<Outcome<T>> runConcurrently(List<Supplier<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(operations.size(), runnable -> {
            Thread thread = new Thread(runnable, "notification-postgres-" + threadNumber.incrementAndGet());
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

    private void assertNoFailures(List<? extends Outcome<?>> outcomes, String operation) {
        for (Outcome<?> outcome : outcomes) {
            assertThat(outcome.error())
                    .as("Concurrent %s request must complete without an exception", operation)
                    .isNull();
        }
    }

    private void assertOneSuccessAndOnlyNotFoundFailures(
            List<? extends Outcome<?>> outcomes,
            String operation
    ) {
        assertThat(outcomes.stream().filter(outcome -> outcome.error() == null).count())
                .as("Exactly one concurrent request must %s", operation)
                .isEqualTo(1L);
        outcomes.stream()
                .map(Outcome::error)
                .filter(error -> error != null)
                .forEach(error -> assertThat(error)
                        .as("Competing requests may only fail because the source no longer exists")
                        .isInstanceOfSatisfying(ResponseStatusException.class, responseError ->
                                assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
    }

    private void assertSuccessOrPostNotFound(Outcome<?> outcome, String operation) {
        if (outcome.error() == null) {
            return;
        }
        assertThat(outcome.error())
                .as("%s may only lose the race to a valid post deletion", operation)
                .isInstanceOfSatisfying(ResponseStatusException.class, responseError -> {
                    assertThat(responseError.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(responseError.getReason()).isEqualTo("Post not found.");
                });
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private UUID singleUuid(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, UUID.class, arguments);
    }

    private record Fixture(
            UUID recipientId,
            String recipientHandle,
            CustomUserPrincipal recipientPrincipal,
            UUID actorId,
            CustomUserPrincipal actorPrincipal,
            UUID postId
    ) {
    }

    private record Actor(UUID id, CustomUserPrincipal principal) {
    }

    private record MultiActorFixture(
            UUID recipientId,
            String recipientHandle,
            CustomUserPrincipal recipientPrincipal,
            UUID postId,
            List<Actor> actors
    ) {
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
            String url = requiredEnvironment("NOTIFICATION_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("NOTIFICATION_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("NOTIFICATION_POSTGRES_TEST_PASSWORD", true);
            String schema = "notification_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL notification test.");
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
                throw new IllegalStateException("Could not prepare the PostgreSQL notification test schema.", error);
            }
        }
    }
}
