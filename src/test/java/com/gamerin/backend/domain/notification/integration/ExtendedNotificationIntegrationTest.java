package com.gamerin.backend.domain.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringReviewRequest;
import com.gamerin.backend.domain.mentoring.entity.ApplicationStatus;
import com.gamerin.backend.domain.mentoring.entity.MentorProfile;
import com.gamerin.backend.domain.mentoring.entity.MentoringApplication;
import com.gamerin.backend.domain.mentoring.entity.MentoringProgram;
import com.gamerin.backend.domain.mentoring.entity.PaymentStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringApplicationRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringProgramRepository;
import com.gamerin.backend.domain.mentoring.repository.MentoringReviewRepository;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.mentoring.service.SettlementProcessor;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.repost.repository.PostRepostRepository;
import com.gamerin.backend.domain.repost.service.PostRepostService;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.MileageTransactionRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ExtendedNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostRepostRepository postRepostRepository;
    @Autowired
    private PostRepostService postRepostService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageConversationRepository messageConversationRepository;
    @Autowired
    private MessageParticipantRepository messageParticipantRepository;
    @Autowired
    private DirectMessageRepository directMessageRepository;
    @Autowired
    private DirectMessageAttachmentRepository directMessageAttachmentRepository;
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
    private MentoringReviewRepository mentoringReviewRepository;
    @Autowired
    private MileageWalletRepository mileageWalletRepository;
    @Autowired
    private MileageTransactionRepository mileageTransactionRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        notificationRepository.deleteAllInBatch();
        directMessageAttachmentRepository.deleteAllInBatch();
        directMessageRepository.deleteAllInBatch();
        messageParticipantRepository.deleteAllInBatch();
        messageConversationRepository.deleteAllInBatch();
        postRepostRepository.deleteAllInBatch();
        mentoringReviewRepository.deleteAllInBatch();
        mentoringApplicationRepository.deleteAllInBatch();
        mentoringProgramRepository.deleteAllInBatch();
        mentorProfileRepository.deleteAllInBatch();
        mileageTransactionRepository.deleteAllInBatch();
        mileageWalletRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void repostNotificationIsIdempotentAndRemovedWithRepost() throws Exception {
        User author = saveUser("author");
        User actor = saveUser("reposter");
        Post post = postRepository.saveAndFlush(Post.create(author, "target"));

        postRepostService.repost(principal(actor), post.getId());
        postRepostService.repost(principal(actor), post.getId());

        assertThat(count("select count(*) from post_reposts where post_id = ?", post.getId())).isEqualTo(1);
        assertThat(count(
                "select count(*) from notifications where type = 'REPOST' and post_id = ?",
                post.getId()
        )).isEqualTo(1);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("repost"))
                .andExpect(jsonPath("$.data.items[0].actor.userId").value(actor.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].postId").value(post.getId().toString()));

        postRepostService.unrepost(principal(actor), post.getId());
        postRepostService.unrepost(principal(actor), post.getId());

        assertThat(count("select count(*) from post_reposts where post_id = ?", post.getId())).isZero();
        assertThat(count(
                "select count(*) from notifications where type = 'REPOST' and post_id = ?",
                post.getId()
        )).isZero();
    }

    @Test
    void directMessagesUseOneConversationNotificationAndTrackReadAndDelete() throws Exception {
        User sender = saveUser("sender");
        User recipient = saveUser("recipient");
        MessageConversation conversation = createConversation(sender, recipient);

        UUID firstMessageId = messageService.sendMessage(
                principal(sender),
                conversation.getId(),
                new SendMessageRequest("first", null)
        ).id();
        UUID secondMessageId = messageService.sendMessage(
                principal(sender),
                conversation.getId(),
                new SendMessageRequest("second", null)
        ).id();

        assertThat(countDirectMessageNotifications(recipient, conversation)).isEqualTo(1);
        assertThat(singleUuid(
                "select message_id from notifications where recipient_id = ? and conversation_id = ?",
                recipient.getId(),
                conversation.getId()
        )).isEqualTo(secondMessageId);
        assertThat(singleTimestamp(
                "select read_at from notifications where recipient_id = ? and conversation_id = ?",
                recipient.getId(),
                conversation.getId()
        )).isNull();
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("direct_message"))
                .andExpect(jsonPath("$.data.items[0].conversationId")
                        .value(conversation.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].messageId").value(secondMessageId.toString()));

        messageService.markRead(principal(recipient), conversation.getId());
        assertThat(singleTimestamp(
                "select read_at from notifications where recipient_id = ? and conversation_id = ?",
                recipient.getId(),
                conversation.getId()
        )).isNotNull();

        messageService.sendMessage(
                principal(sender),
                conversation.getId(),
                new SendMessageRequest("third", null)
        );
        assertThat(singleTimestamp(
                "select read_at from notifications where recipient_id = ? and conversation_id = ?",
                recipient.getId(),
                conversation.getId()
        )).isNull();

        messageService.deleteMessage(principal(sender), conversation.getId(), secondMessageId);
        assertThat(countDirectMessageNotifications(recipient, conversation)).isEqualTo(1);
        assertThat(singleUuid(
                "select message_id from notifications where recipient_id = ? and conversation_id = ?",
                recipient.getId(),
                conversation.getId()
        )).isNotEqualTo(secondMessageId);

        messageService.leaveConversation(principal(recipient), conversation.getId());
        assertThat(countDirectMessageNotifications(recipient, conversation)).isZero();
        assertThat(firstMessageId).isNotNull();
    }

    @Test
    void sharedPostMessageAlsoCreatesDirectMessageNotification() {
        User sender = saveUser("sender");
        User recipient = saveUser("recipient");
        Post post = postRepository.saveAndFlush(Post.create(sender, "shared"));
        MessageConversation conversation = createConversation(sender, recipient);

        var response = messageService.sendMessage(
                principal(sender),
                conversation.getId(),
                new SendMessageRequest(null, post.getId())
        );

        assertThat(response.sharedPost()).isNotNull();
        assertThat(count(
                "select count(*) from notifications where type = 'DIRECT_MESSAGE' and recipient_id = ?",
                recipient.getId()
        )).isEqualTo(1);
    }

    @Test
    void mentoringLifecycleCreatesOneNotificationPerSuccessfulTransition() {
        MentoringFixture fixture = createMentoringFixture();

        MentoringApplication application = mentoringApplicationRepository.findById(
                mentoringService.applyToProgram(
                        principal(fixture.mentee()),
                        new MentoringApplicationRequest(fixture.program().getId(), "help")
                ).id()
        ).orElseThrow();
        assertNotification("MENTORING_APPLICATION", fixture.mentor(), fixture.mentee(), application);

        mentoringService.acceptApplication(principal(fixture.mentor()), application.getId());
        assertNotification("MENTORING_ACCEPTED", fixture.mentee(), fixture.mentor(), application);

        mentoringService.startMentoring(principal(fixture.mentor()), application.getId());
        assertNotification("MENTORING_STARTED", fixture.mentee(), fixture.mentor(), application);

        mentoringService.finishMentoring(principal(fixture.mentor()), application.getId());
        assertNotification("MENTORING_FINISHED", fixture.mentee(), fixture.mentor(), application);

        mentoringService.completeMentoring(principal(fixture.mentee()), application.getId());
        assertNotification("MENTORING_COMPLETED", fixture.mentor(), fixture.mentee(), application);

        mentoringService.createReview(
                principal(fixture.mentee()),
                new MentoringReviewRequest(application.getId(), 5, "great")
        );
        assertNotification("MENTORING_REVIEW", fixture.mentor(), fixture.mentee(), application);
        assertThat(count(
                "select count(*) from notifications where mentoring_application_id = ?",
                application.getId()
        )).isEqualTo(6);
    }

    @Test
    void mentoringCancellationAndRejectionNotifyOnlyTheOtherParticipant() {
        MentoringFixture cancellation = createMentoringFixture();
        UUID cancelledId = mentoringService.applyToProgram(
                principal(cancellation.mentee()),
                new MentoringApplicationRequest(cancellation.program().getId(), "cancel")
        ).id();
        mentoringService.cancelApplication(principal(cancellation.mentee()), cancelledId);
        assertThat(count(
                "select count(*) from notifications where type = 'MENTORING_CANCELLED' and recipient_id = ?",
                cancellation.mentor().getId()
        )).isEqualTo(1);

        MentoringFixture rejection = createMentoringFixture();
        UUID rejectedId = mentoringService.applyToProgram(
                principal(rejection.mentee()),
                new MentoringApplicationRequest(rejection.program().getId(), "reject")
        ).id();
        mentoringService.rejectApplication(principal(rejection.mentor()), rejectedId);
        assertThat(count(
                "select count(*) from notifications where type = 'MENTORING_REJECTED' and recipient_id = ?",
                rejection.mentee().getId()
        )).isEqualTo(1);
    }

    @Test
    void automaticSettlementIsIdempotentAndSystemNotificationsHaveNullActor() throws Exception {
        MentoringFixture fixture = createMentoringFixture();
        MentoringApplication application = mentoringApplicationRepository.saveAndFlush(
                mentoringApplication(fixture, ApplicationStatus.FINISHED)
        );
        OffsetDateTime oldUpdatedAt = OffsetDateTime.now().minusDays(8);
        jdbcTemplate.update(
                "update mentoring_applications set updated_at = ? where id = ?",
                oldUpdatedAt,
                application.getId()
        );
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(7);

        settlementProcessor.processSingleSettlement(application.getId(), threshold);
        settlementProcessor.processSingleSettlement(application.getId(), threshold);

        assertThat(count(
                "select count(*) from notifications where type = 'MENTORING_COMPLETED' and mentoring_application_id = ?",
                application.getId()
        )).isEqualTo(2);
        assertThat(count(
                "select count(*) from mileage_transactions where type = 'SETTLEMENT' and reference_id = ?",
                application.getId()
        )).isEqualTo(1);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(fixture.mentor())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("mentoring_completed"))
                .andExpect(jsonPath("$.data.items[0].actor").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].mentoringApplicationId")
                        .value(application.getId().toString()));
    }

    private MessageConversation createConversation(User left, User right) {
        String directKey = List.of(left.getId().toString(), right.getId().toString())
                .stream()
                .sorted()
                .reduce((first, second) -> first + ":" + second)
                .orElseThrow();
        return transactionTemplate.execute(status -> {
            User managedLeft = userRepository.findById(left.getId()).orElseThrow();
            User managedRight = userRepository.findById(right.getId()).orElseThrow();
            MessageConversation conversation = messageConversationRepository.saveAndFlush(
                    MessageConversation.createDirect(directKey)
            );
            messageParticipantRepository.save(MessageParticipant.create(conversation, managedLeft));
            messageParticipantRepository.save(MessageParticipant.create(conversation, managedRight));
            messageParticipantRepository.flush();
            return conversation;
        });
    }

    private MentoringFixture createMentoringFixture() {
        User mentor = saveUser("mentor");
        User mentee = saveUser("mentee");

        return transactionTemplate.execute(status -> {
            User managedMentor = userRepository.findById(mentor.getId()).orElseThrow();
            User managedMentee = userRepository.findById(mentee.getId()).orElseThrow();

            MentorProfile profile = new MentorProfile();
            profile.setUser(managedMentor);
            profile = mentorProfileRepository.saveAndFlush(profile);

            MentoringProgram program = new MentoringProgram();
            program.setMentor(profile);
            program.setGameName("PUBG");
            program.setTitle("coaching");
            program.setPrice(100L);
            program.setTags(List.of());
            program = mentoringProgramRepository.saveAndFlush(program);

            MileageWallet wallet = new MileageWallet();
            wallet.setUser(managedMentee);
            wallet.setBalance(1_000L);
            mileageWalletRepository.saveAndFlush(wallet);

            return new MentoringFixture(managedMentor, managedMentee, program);
        });
    }

    private MentoringApplication mentoringApplication(MentoringFixture fixture, ApplicationStatus status) {
        MentoringApplication application = new MentoringApplication();
        application.setProgram(fixture.program());
        application.setMentee(fixture.mentee());
        application.setAppliedMileage(100L);
        application.setStatus(status);
        application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
        application.setMessage("finished");
        return application;
    }

    private void assertNotification(
            String type,
            User recipient,
            User actor,
            MentoringApplication application
    ) {
        assertThat(count(
                """
                select count(*) from notifications
                where type = ? and recipient_id = ? and actor_id = ? and mentoring_application_id = ?
                """,
                type,
                recipient.getId(),
                actor.getId(),
                application.getId()
        )).isEqualTo(1);
    }

    private long countDirectMessageNotifications(User recipient, MessageConversation conversation) {
        return count(
                """
                select count(*) from notifications
                where type = 'DIRECT_MESSAGE' and recipient_id = ? and conversation_id = ?
                """,
                recipient.getId(),
                conversation.getId()
        );
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = User.createLocal(
                prefix + suffix + "@example.com",
                prefix + suffix,
                prefix,
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private CustomUserPrincipal principal(User user) {
        return CustomUserPrincipal.from(user);
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private UUID singleUuid(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, UUID.class, arguments);
    }

    private OffsetDateTime singleTimestamp(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, OffsetDateTime.class, arguments);
    }

    private record MentoringFixture(User mentor, User mentee, MentoringProgram program) {
    }
}
