package com.gamerin.backend.domain.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.mentoring.service.SettlementProcessor;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.notification.service.NotificationQueryService;
import com.gamerin.backend.domain.post.dto.request.CreateCommentRequest;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.ShareTarget;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostCleanupService;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.repost.service.PostRepostService;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.service.MileageService;
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
    private PostRepostService postRepostService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageConversationRepository messageConversationRepository;
    @Autowired
    private MessageParticipantRepository messageParticipantRepository;
    @Autowired
    private MentoringService mentoringService;
    @Autowired
    private SettlementProcessor settlementProcessor;
    @Autowired
    private MentorProfileRepository mentorProfileRepository;
    @Autowired
    private MentoringProgramRepository mentoringProgramRepository;
    @Autowired
    private MentoringApplicationRepository mentoringApplicationRepository;
    @Autowired
    private MileageWalletRepository mileageWalletRepository;
    @Autowired
    private MileageService mileageService;
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
                "update notifications set event_at = timestamp with time zone '2026-08-12 10:00:00+09' "
                        + "where recipient_id = ?",
                fixture.recipientId()
        );
        List<UUID> expectedOrder = jdbcTemplate.queryForList(
                "select id from notifications where recipient_id = ? order by event_at desc, id desc",
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

    @Test
    void concurrentDuplicateRepostsKeepOneSourceAndNotification() throws Exception {
        Fixture fixture = createFixture();
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(ignored -> (Supplier<Void>) () -> {
                    postRepostService.repost(fixture.actorPrincipal(), fixture.postId());
                    return null;
                })
                .toList();

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertNoFailures(outcomes, "repost");
        assertThat(count(
                "select count(*) from post_reposts where post_id = ? and user_id = ?",
                fixture.postId(),
                fixture.actorId()
        )).isEqualTo(1);
        assertThat(count(
                "select count(*) from notifications where type = 'REPOST' and post_id = ?",
                fixture.postId()
        )).isEqualTo(1);
    }

    @Test
    void concurrentMessagesAndReadKeepOneConversationNotificationConsistent() throws Exception {
        MessageFixture fixture = createMessageFixture();
        List<Supplier<Void>> sends = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Void>) () -> {
                    messageService.sendMessage(
                            fixture.senderPrincipal(),
                            fixture.conversationId(),
                            new SendMessageRequest("message-" + index, null)
                    );
                    return null;
                })
                .toList();

        assertNoFailures(runConcurrently(sends), "direct messages");
        assertThat(count(
                "select count(*) from direct_messages where conversation_id = ?",
                fixture.conversationId()
        )).isEqualTo(CONCURRENT_REQUESTS);
        assertThat(count(
                """
                select count(*) from notifications
                where type = 'DIRECT_MESSAGE' and recipient_id = ? and conversation_id = ?
                """,
                fixture.recipientId(),
                fixture.conversationId()
        )).isEqualTo(1);
        UUID latestMessageId = singleUuid(
                """
                select id from direct_messages
                where conversation_id = ? and deleted_at is null
                order by created_at desc, id desc limit 1
                """,
                fixture.conversationId()
        );
        assertThat(singleUuid(
                "select message_id from notifications where recipient_id = ? and conversation_id = ?",
                fixture.recipientId(),
                fixture.conversationId()
        )).isEqualTo(latestMessageId);

        List<Supplier<Void>> sendAndRead = List.of(
                () -> {
                    messageService.sendMessage(
                            fixture.senderPrincipal(),
                            fixture.conversationId(),
                            new SendMessageRequest("racing-message", null)
                    );
                    return null;
                },
                () -> {
                    messageService.markRead(fixture.recipientPrincipal(), fixture.conversationId());
                    return null;
                }
        );
        assertNoFailures(runConcurrently(sendAndRead), "direct message and read");

        OffsetDateTime lastReadAt = jdbcTemplate.queryForObject(
                """
                select last_read_at from message_participants
                where conversation_id = ? and user_id = ?
                """,
                OffsetDateTime.class,
                fixture.conversationId(),
                fixture.recipientId()
        );
        long unreadMessages = lastReadAt == null
                ? count(
                        """
                        select count(*) from direct_messages
                        where conversation_id = ? and sender_id <> ? and deleted_at is null
                        """,
                        fixture.conversationId(),
                        fixture.recipientId()
                )
                : count(
                        """
                        select count(*) from direct_messages
                        where conversation_id = ? and sender_id <> ? and deleted_at is null
                          and created_at > ?
                        """,
                        fixture.conversationId(),
                        fixture.recipientId(),
                        lastReadAt
                );
        OffsetDateTime notificationReadAt = jdbcTemplate.queryForObject(
                """
                select read_at from notifications
                where recipient_id = ? and conversation_id = ?
                """,
                OffsetDateTime.class,
                fixture.recipientId(),
                fixture.conversationId()
        );
        if (unreadMessages == 0) {
            assertThat(notificationReadAt).isNotNull();
        } else {
            assertThat(notificationReadAt).isNull();
        }
    }

    @Test
    void concurrentMentoringAcceptAndRejectApplyExactlyOneTransition() throws Exception {
        MentoringFixture fixture = createMentoringFixture(ApplicationStatus.APPLIED, 900L);
        List<Supplier<Void>> operations = List.of(
                () -> {
                    mentoringService.acceptApplication(fixture.mentorPrincipal(), fixture.applicationId());
                    return null;
                },
                () -> {
                    mentoringService.rejectApplication(fixture.mentorPrincipal(), fixture.applicationId());
                    return null;
                }
        );

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertThat(outcomes.stream().filter(outcome -> outcome.error() == null).count()).isEqualTo(1);
        String finalStatus = jdbcTemplate.queryForObject(
                "select status from mentoring_applications where id = ?",
                String.class,
                fixture.applicationId()
        );
        assertThat(finalStatus).isIn("ACCEPTED", "REJECTED");
        assertThat(count(
                """
                select count(*) from notifications
                where mentoring_application_id = ?
                  and type in ('MENTORING_ACCEPTED', 'MENTORING_REJECTED')
                """,
                fixture.applicationId()
        )).isEqualTo(1);
        long refundCount = count(
                """
                select count(*) from mileage_transactions
                where reference_id = ? and type = 'MENTORING_REFUND'
                """,
                fixture.applicationId()
        );
        long menteeBalance = count(
                "select balance from mileage_wallets where user_id = ?",
                fixture.menteeId()
        );
        if ("REJECTED".equals(finalStatus)) {
            assertThat(refundCount).isEqualTo(1);
            assertThat(menteeBalance).isEqualTo(1_000);
        } else {
            assertThat(refundCount).isZero();
            assertThat(menteeBalance).isEqualTo(900);
        }
    }

    @Test
    void concurrentManualAndAutomaticSettlementPayExactlyOnce() throws Exception {
        MentoringFixture fixture = createMentoringFixture(ApplicationStatus.FINISHED, 1_000L);
        OffsetDateTime oldUpdatedAt = OffsetDateTime.now().minusDays(8);
        jdbcTemplate.update(
                "update mentoring_applications set updated_at = ? where id = ?",
                oldUpdatedAt,
                fixture.applicationId()
        );
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);
        List<Supplier<Void>> operations = List.of(
                () -> {
                    mentoringService.completeMentoring(fixture.menteePrincipal(), fixture.applicationId());
                    return null;
                },
                () -> {
                    settlementProcessor.processSingleSettlement(fixture.applicationId(), threshold);
                    return null;
                }
        );

        List<Outcome<Void>> outcomes = runConcurrently(operations);

        assertThat(outcomes.stream().filter(outcome -> outcome.error() == null).count()).isBetween(1L, 2L);
        assertThat(jdbcTemplate.queryForObject(
                "select status from mentoring_applications where id = ?",
                String.class,
                fixture.applicationId()
        )).isEqualTo("COMPLETED");
        assertThat(count(
                "select count(*) from mileage_transactions where reference_id = ? and type = 'SETTLEMENT'",
                fixture.applicationId()
        )).isEqualTo(1);
        assertThat(count(
                "select balance from mileage_wallets where user_id = ?",
                fixture.mentorId()
        )).isEqualTo(100);
        assertThat(count(
                "select mentee_count from mentor_profiles where user_id = ?",
                fixture.mentorId()
        )).isEqualTo(1);
        assertThat(count(
                """
                select count(*) from notifications
                where mentoring_application_id = ? and type = 'MENTORING_COMPLETED'
                """,
                fixture.applicationId()
        )).isBetween(1L, 2L);
    }

    @Test
    void concurrentMileageCreditsDoNotLoseBalanceUpdates() throws Exception {
        User user = transactionTemplate.execute(status -> {
            User saved = saveUser("mileage-user");
            MileageWallet wallet = new MileageWallet();
            wallet.setUser(saved);
            wallet.setBalance(0L);
            mileageWalletRepository.saveAndFlush(wallet);
            return saved;
        });
        List<Supplier<Void>> operations = IntStream.range(0, CONCURRENT_REQUESTS)
                .mapToObj(index -> (Supplier<Void>) () -> {
                    User managed = userRepository.findById(user.getId()).orElseThrow();
                    mileageService.addMileage(
                            managed,
                            10L,
                            TransactionType.CHARGE,
                            "concurrent credit",
                            UUID.randomUUID()
                    );
                    return null;
                })
                .toList();

        assertNoFailures(runConcurrently(operations), "mileage credits");
        assertThat(count("select balance from mileage_wallets where user_id = ?", user.getId()))
                .isEqualTo(CONCURRENT_REQUESTS * 10L);
        assertThat(count(
                "select count(*) from mileage_transactions where user_id = ? and type = 'CHARGE'",
                user.getId()
        )).isEqualTo(CONCURRENT_REQUESTS);
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

    private MessageFixture createMessageFixture() {
        return transactionTemplate.execute(status -> {
            User sender = saveUser("message-sender");
            User recipient = saveUser("message-recipient");
            String directKey = List.of(sender.getId().toString(), recipient.getId().toString())
                    .stream()
                    .sorted()
                    .reduce((left, right) -> left + ":" + right)
                    .orElseThrow();
            MessageConversation conversation = messageConversationRepository.saveAndFlush(
                    MessageConversation.createDirect(directKey)
            );
            messageParticipantRepository.save(MessageParticipant.create(conversation, sender));
            messageParticipantRepository.save(MessageParticipant.create(conversation, recipient));
            messageParticipantRepository.flush();
            return new MessageFixture(
                    conversation.getId(),
                    sender.getId(),
                    CustomUserPrincipal.from(sender),
                    recipient.getId(),
                    CustomUserPrincipal.from(recipient)
            );
        });
    }

    private MentoringFixture createMentoringFixture(ApplicationStatus status, long menteeBalance) {
        return transactionTemplate.execute(transactionStatus -> {
            User mentor = saveUser("mentor");
            User mentee = saveUser("mentee");

            MentorProfile profile = new MentorProfile();
            profile.setUser(mentor);
            profile = mentorProfileRepository.saveAndFlush(profile);

            MentoringProgram program = new MentoringProgram();
            program.setMentor(profile);
            program.setGameName("PUBG");
            program.setTitle("concurrency coaching");
            program.setPrice(100L);
            program.setTags(List.of());
            program = mentoringProgramRepository.saveAndFlush(program);

            MileageWallet menteeWallet = new MileageWallet();
            menteeWallet.setUser(mentee);
            menteeWallet.setBalance(menteeBalance);
            mileageWalletRepository.save(menteeWallet);

            MileageWallet mentorWallet = new MileageWallet();
            mentorWallet.setUser(mentor);
            mentorWallet.setBalance(0L);
            mileageWalletRepository.save(mentorWallet);
            mileageWalletRepository.flush();

            MentoringApplication application = new MentoringApplication();
            application.setProgram(program);
            application.setMentee(mentee);
            application.setAppliedMileage(100L);
            application.setStatus(status);
            application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
            application.setMessage("concurrency test");
            application = mentoringApplicationRepository.saveAndFlush(application);

            return new MentoringFixture(
                    mentor.getId(),
                    CustomUserPrincipal.from(mentor),
                    mentee.getId(),
                    CustomUserPrincipal.from(mentee),
                    application.getId()
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

    private record MessageFixture(
            UUID conversationId,
            UUID senderId,
            CustomUserPrincipal senderPrincipal,
            UUID recipientId,
            CustomUserPrincipal recipientPrincipal
    ) {
    }

    private record MentoringFixture(
            UUID mentorId,
            CustomUserPrincipal mentorPrincipal,
            UUID menteeId,
            CustomUserPrincipal menteePrincipal,
            UUID applicationId
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
