package com.gamerin.backend.domain.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
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
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.entity.MessageParticipant;
import com.gamerin.backend.domain.message.repository.DirectMessageAttachmentRepository;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.PostLike;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.repost.entity.PostRepost;
import com.gamerin.backend.domain.repost.repository.PostRepostRepository;
import com.gamerin.backend.domain.user.entity.MileageWallet;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.repository.MileageTransactionRepository;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationTransactionRollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private PostLikeRepository postLikeRepository;
    @Autowired
    private PostCommentRepository postCommentRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private PostRepostRepository postRepostRepository;
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
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private NotificationCommandService notificationCommandService;

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
        postLikeRepository.deleteAllInBatch();
        postCommentRepository.deleteAllInBatch();
        followRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void likeRollsBackWhenNotificationCreationFails() throws Exception {
        Fixture fixture = createFixture();
        doThrow(notificationFailure()).when(notificationCommandService).createLike(any(), any(), any());

        mockMvc.perform(post("/api/v1/posts/{postId}/likes", fixture.post().getId())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postLikeRepository.count()).isZero();
        assertThat(reloadPost(fixture).getLikeCount()).isZero();
    }

    @Test
    void commentRollsBackWhenNotificationCreationFails() throws Exception {
        Fixture fixture = createFixture();
        doThrow(notificationFailure()).when(notificationCommandService).createComment(any(), any(), any());

        mockMvc.perform(post("/api/v1/posts/{postId}/comments", fixture.post().getId())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hello"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postCommentRepository.count()).isZero();
        assertThat(reloadPost(fixture).getCommentCount()).isZero();
    }

    @Test
    void followRollsBackWhenNotificationCreationFails() throws Exception {
        Fixture fixture = createFixture();
        doThrow(notificationFailure()).when(notificationCommandService).createFollow(any(), any(), any());

        mockMvc.perform(post("/api/v1/users/{handle}/follow", fixture.recipient().getHandle())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(followRepository.count()).isZero();
    }

    @Test
    void unlikeRollsBackWhenNotificationRemovalFails() throws Exception {
        Fixture fixture = createFixture();
        seedLike(fixture);
        doThrow(notificationFailure()).when(notificationCommandService)
                .removeLike(fixture.post().getId(), fixture.actor().getId());

        mockMvc.perform(delete("/api/v1/posts/{postId}/likes", fixture.post().getId())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postLikeRepository.count()).isEqualTo(1L);
        assertThat(reloadPost(fixture).getLikeCount()).isEqualTo(1L);
    }

    @Test
    void commentDeleteRollsBackWhenNotificationRemovalFails() throws Exception {
        Fixture fixture = createFixture();
        PostComment comment = seedComment(fixture);
        doThrow(notificationFailure()).when(notificationCommandService).removeComment(comment.getId());

        mockMvc.perform(delete(
                        "/api/v1/posts/{postId}/comments/{commentId}",
                        fixture.post().getId(),
                        comment.getId()
                )
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postCommentRepository.findById(comment.getId())).isPresent();
        assertThat(reloadPost(fixture).getCommentCount()).isEqualTo(1L);
    }

    @Test
    void unfollowRollsBackWhenNotificationRemovalFails() throws Exception {
        Fixture fixture = createFixture();
        seedFollow(fixture);
        doThrow(notificationFailure()).when(notificationCommandService)
                .removeFollow(fixture.actor().getId(), fixture.recipient().getId());

        mockMvc.perform(delete("/api/v1/users/{handle}/follow", fixture.recipient().getHandle())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(followRepository.count()).isEqualTo(1L);
    }

    @Test
    void repostRollsBackWhenNotificationCreationFails() throws Exception {
        Fixture fixture = createFixture();
        doThrow(notificationFailure()).when(notificationCommandService).createRepost(any(), any(), any());

        mockMvc.perform(post("/api/v1/posts/{postId}/reposts", fixture.post().getId())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postRepostRepository.count()).isZero();
    }

    @Test
    void unrepostRollsBackWhenNotificationRemovalFails() throws Exception {
        Fixture fixture = createFixture();
        postRepostRepository.saveAndFlush(PostRepost.create(fixture.post(), fixture.actor()));
        doThrow(notificationFailure()).when(notificationCommandService)
                .removeRepost(fixture.post().getId(), fixture.actor().getId());

        mockMvc.perform(delete("/api/v1/posts/{postId}/reposts", fixture.post().getId())
                        .header(HttpHeaders.AUTHORIZATION, fixture.actorToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(postRepostRepository.count()).isEqualTo(1);
    }

    @Test
    void messageRollsBackWhenNotificationCreationFails() {
        MessageFixture fixture = createMessageFixture();
        doThrow(notificationFailure()).when(notificationCommandService)
                .createOrRefreshDirectMessage(any(), any(), any(), any());

        assertThatThrownBy(() -> messageService.sendMessage(
                com.gamerin.backend.global.security.principal.CustomUserPrincipal.from(fixture.sender()),
                fixture.conversation().getId(),
                new SendMessageRequest("hello", null)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(directMessageRepository.count()).isZero();
        MessageConversation conversation = messageConversationRepository.findById(fixture.conversation().getId())
                .orElseThrow();
        assertThat(conversation.getLastMessageId()).isNull();
    }

    @Test
    void mentoringRejectionAndRefundRollBackWhenNotificationCreationFails() {
        MentoringFixture fixture = createMentoringFixture();
        doThrow(notificationFailure()).when(notificationCommandService)
                .createMentoringRejected(any(), any(), any());

        assertThatThrownBy(() -> mentoringService.rejectApplication(
                com.gamerin.backend.global.security.principal.CustomUserPrincipal.from(fixture.mentor()),
                fixture.application().getId()
        )).isInstanceOf(IllegalStateException.class);

        MentoringApplication application = mentoringApplicationRepository.findById(fixture.application().getId())
                .orElseThrow();
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.ESCROW_HELD);
        assertThat(mileageWalletRepository.findById(fixture.mentee().getId()).orElseThrow().getBalance())
                .isEqualTo(900L);
        assertThat(mileageTransactionRepository.count()).isZero();
    }

    private Fixture createFixture() {
        User recipient = saveUser("recipient");
        User actor = saveUser("actor");
        Post post = postRepository.saveAndFlush(Post.create(recipient, "target post"));
        return new Fixture(recipient, actor, post, bearerToken(actor));
    }

    private MessageFixture createMessageFixture() {
        return transactionTemplate.execute(status -> {
            User sender = saveUser("sender");
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
            return new MessageFixture(sender, recipient, conversation);
        });
    }

    private MentoringFixture createMentoringFixture() {
        return transactionTemplate.execute(status -> {
            User mentor = saveUser("mentor");
            User mentee = saveUser("mentee");

            MentorProfile profile = new MentorProfile();
            profile.setUser(mentor);
            profile = mentorProfileRepository.saveAndFlush(profile);

            MentoringProgram program = new MentoringProgram();
            program.setMentor(profile);
            program.setGameName("PUBG");
            program.setTitle("rollback coaching");
            program.setPrice(100L);
            program.setTags(List.of());
            program = mentoringProgramRepository.saveAndFlush(program);

            MileageWallet wallet = new MileageWallet();
            wallet.setUser(mentee);
            wallet.setBalance(900L);
            mileageWalletRepository.saveAndFlush(wallet);

            MentoringApplication application = new MentoringApplication();
            application.setProgram(program);
            application.setMentee(mentee);
            application.setAppliedMileage(100L);
            application.setStatus(ApplicationStatus.APPLIED);
            application.setPaymentStatus(PaymentStatus.ESCROW_HELD);
            application.setMessage("rollback");
            application = mentoringApplicationRepository.saveAndFlush(application);
            return new MentoringFixture(mentor, mentee, application);
        });
    }

    private void seedLike(Fixture fixture) {
        postLikeRepository.saveAndFlush(PostLike.create(fixture.post(), fixture.actor()));
        fixture.post().increaseLikeCount();
        postRepository.saveAndFlush(fixture.post());
    }

    private PostComment seedComment(Fixture fixture) {
        PostComment comment = postCommentRepository.saveAndFlush(
                PostComment.create(fixture.post(), fixture.actor(), "comment")
        );
        fixture.post().increaseCommentCount();
        postRepository.saveAndFlush(fixture.post());
        return comment;
    }

    private void seedFollow(Fixture fixture) {
        followRepository.saveAndFlush(Follow.create(fixture.actor(), fixture.recipient()));
    }

    private Post reloadPost(Fixture fixture) {
        return postRepository.findById(fixture.post().getId()).orElseThrow();
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

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private IllegalStateException notificationFailure() {
        return new IllegalStateException("notification persistence failed");
    }

    private record Fixture(User recipient, User actor, Post post, String actorToken) {
    }

    private record MessageFixture(User sender, User recipient, MessageConversation conversation) {
    }

    private record MentoringFixture(User mentor, User mentee, MentoringApplication application) {
    }
}
