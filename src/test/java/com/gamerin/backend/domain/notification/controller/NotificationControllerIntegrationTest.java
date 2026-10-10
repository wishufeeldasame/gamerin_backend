package com.gamerin.backend.domain.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostLikeRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerIntegrationTest {

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
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        notificationRepository.deleteAllInBatch();
        postLikeRepository.deleteAllInBatch();
        postCommentRepository.deleteAllInBatch();
        followRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void allNotificationEndpointsRequireAuthentication() throws Exception {
        UUID notificationId = UUID.randomUUID();
        List<RequestBuilder> requests = List.of(
                get("/api/v1/notifications"),
                get("/api/v1/notifications/unread-count"),
                patch("/api/v1/notifications/{notificationId}/read", notificationId),
                patch("/api/v1/notifications/read-all")
        );

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Test
    void likeNotificationSupportsDuplicateReadOwnershipAndCancellationPolicies() throws Exception {
        User recipient = saveUser("recipient");
        User actor = saveUser("actor");
        Post targetPost = savePost(recipient, "target post");
        String recipientToken = bearerToken(recipient);
        String actorToken = bearerToken(actor);

        like(actorToken, targetPost.getId());
        like(actorToken, targetPost.getId());

        assertThat(notificationRepository.count()).isEqualTo(1L);
        MvcResult listResult = mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("like"))
                .andExpect(jsonPath("$.data.items[0].actor.userId").value(actor.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].actor.handle").value(actor.getHandle()))
                .andExpect(jsonPath("$.data.items[0].postId").value(targetPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].commentId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andReturn();
        UUID notificationId = UUID.fromString(body(listResult)
                .path("data").path("items").get(0).path("notificationId").asText());

        assertUnreadCount(recipientToken, 1);
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, actorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        markRead(recipientToken, notificationId);
        markRead(recipientToken, notificationId);
        assertUnreadCount(recipientToken, 0);

        mockMvc.perform(delete("/api/v1/posts/{postId}/likes", targetPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, actorToken))
                .andExpect(status().isOk());

        assertThat(notificationRepository.count()).isZero();
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));

        like(actorToken, targetPost.getId());
        UUID recreatedNotificationId = notificationRepository.findAll().getFirst().getId();
        assertThat(recreatedNotificationId).isNotEqualTo(notificationId);
        assertUnreadCount(recipientToken, 1);
    }

    @Test
    void notificationListsAreStrictlyIsolatedByRecipient() throws Exception {
        User firstRecipient = saveUser("firstRecipient");
        User secondRecipient = saveUser("secondRecipient");
        User actor = saveUser("actor");
        Post firstPost = savePost(firstRecipient, "first target");
        Post secondPost = savePost(secondRecipient, "second target");
        String actorToken = bearerToken(actor);

        like(actorToken, firstPost.getId());
        like(actorToken, secondPost.getId());

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(firstRecipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(firstPost.getId().toString()));
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(secondRecipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(secondPost.getId().toString()));
        assertUnreadCount(bearerToken(firstRecipient), 1);
        assertUnreadCount(bearerToken(secondRecipient), 1);
    }

    @Test
    void commentFollowAndLikeNotificationsUseCursorAndReadAllContract() throws Exception {
        User recipient = saveUser("recipient");
        User firstActor = saveUser("firstActor");
        User secondActor = saveUser("secondActor");
        Post targetPost = savePost(recipient, "target post");
        String recipientToken = bearerToken(recipient);
        String firstActorToken = bearerToken(firstActor);
        String secondActorToken = bearerToken(secondActor);

        MvcResult commentResult = mockMvc.perform(post("/api/v1/posts/{postId}/comments", targetPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, firstActorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hello"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(body(commentResult).path("data").path("commentId").asText());

        mockMvc.perform(post("/api/v1/users/{handle}/follow", recipient.getHandle())
                        .header(HttpHeaders.AUTHORIZATION, secondActorToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/users/{handle}/follow", recipient.getHandle())
                        .header(HttpHeaders.AUTHORIZATION, secondActorToken))
                .andExpect(status().isOk());
        like(secondActorToken, targetPost.getId());

        assertThat(notificationRepository.count()).isEqualTo(3L);
        assertUnreadCount(recipientToken, 3);

        MvcResult firstPageResult = mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken)
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn();
        JsonNode firstPage = body(firstPageResult).path("data");
        String cursor = firstPage.path("nextCursor").asText();

        MvcResult secondPageResult = mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken)
                        .queryParam("size", "2")
                        .queryParam("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn();

        Set<String> notificationIds = new HashSet<>();
        firstPage.path("items").forEach(item -> notificationIds.add(item.path("notificationId").asText()));
        body(secondPageResult).path("data").path("items")
                .forEach(item -> notificationIds.add(item.path("notificationId").asText()));
        assertThat(notificationIds).hasSize(3);

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertUnreadCount(recipientToken, 0);

        mockMvc.perform(delete("/api/v1/posts/{postId}/comments/{commentId}", targetPost.getId(), commentId)
                        .header(HttpHeaders.AUTHORIZATION, firstActorToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/users/{handle}/follow", recipient.getHandle())
                        .header(HttpHeaders.AUTHORIZATION, secondActorToken))
                .andExpect(status().isOk());

        assertThat(notificationRepository.count()).isEqualTo(1L);
    }

    @Test
    void ownPostActionsDoNotCreateNotificationsAndInvalidCursorIsRejected() throws Exception {
        User author = saveUser("author");
        Post post = savePost(author, "own post");
        String token = bearerToken(author);

        like(token, post.getId());
        mockMvc.perform(post("/api/v1/posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "own comment"))))
                .andExpect(status().isOk());

        assertThat(notificationRepository.count()).isZero();
        assertUnreadCount(token, 0);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .queryParam("cursor", "invalid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void softDeletedPostNotificationsAreExcludedFromListAndUnreadCount() throws Exception {
        User recipient = saveUser("recipient");
        User actor = saveUser("actor");
        Post post = savePost(recipient, "target post");
        String recipientToken = bearerToken(recipient);

        like(bearerToken(actor), post.getId());
        post.softDelete();
        postRepository.saveAndFlush(post);

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        assertUnreadCount(recipientToken, 0);
    }

    @Test
    void deletedActorNotificationsAreExcludedFromListUnreadCountAndReadAccess() throws Exception {
        User recipient = saveUser("recipient");
        User actor = saveUser("actor");
        Post post = savePost(recipient, "target post");
        String recipientToken = bearerToken(recipient);

        like(bearerToken(actor), post.getId());
        UUID notificationId = notificationRepository.findAll().getFirst().getId();
        ReflectionTestUtils.setField(actor, "deletedAt", OffsetDateTime.now());
        userRepository.saveAndFlush(actor);

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        assertUnreadCount(recipientToken, 0);
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void softDeletedCommentNotificationIsExcludedFromEveryReadPath() throws Exception {
        User recipient = saveUser("recipient");
        User actor = saveUser("actor");
        Post post = savePost(recipient, "target post");
        String recipientToken = bearerToken(recipient);

        MvcResult commentResult = mockMvc.perform(post("/api/v1/posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hidden comment"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(body(commentResult).path("data").path("commentId").asText());
        UUID notificationId = notificationRepository.findAll().getFirst().getId();
        var comment = postCommentRepository.findById(commentId).orElseThrow();
        comment.softDelete();
        postCommentRepository.saveAndFlush(comment);

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        assertUnreadCount(recipientToken, 0);
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedRecipientCannotUseNotificationEndpointsWithAnOldToken() throws Exception {
        User recipient = saveUser("recipient");
        String recipientToken = bearerToken(recipient);
        ReflectionTestUtils.setField(recipient, "deletedAt", OffsetDateTime.now());
        userRepository.saveAndFlush(recipient);

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, recipientToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void like(String token, UUID postId) throws Exception {
        mockMvc.perform(post("/api/v1/posts/{postId}/likes", postId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    private void markRead(String token, UUID notificationId) throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void assertUnreadCount(String token, long expected) throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(expected));
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

    private Post savePost(User author, String content) {
        return postRepository.saveAndFlush(Post.create(author, content));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
