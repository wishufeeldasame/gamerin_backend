package com.gamerin.backend.domain.mention.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mention.repository.UserMentionRepository;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class MentionNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserMentionRepository userMentionRepository;
    @Autowired
    private PostCommentRepository postCommentRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        notificationRepository.deleteAllInBatch();
        userMentionRepository.deleteAllInBatch();
        postCommentRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void jsonPostCreatesDistinctActiveMentionRelationsAndNotifications() throws Exception {
        User actor = saveUser("actor");
        User firstTarget = saveUser("first");
        User secondTarget = saveUser("second");
        String content = "hello @" + actor.getHandle()
                + " @" + firstTarget.getHandle()
                + " @" + firstTarget.getHandle() + "."
                + " @" + secondTarget.getHandle()
                + " @missing";

        MvcResult result = mockMvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(content))
                .andReturn();
        UUID postId = UUID.fromString(body(result).path("data").path("postId").asText());

        assertThat(userMentionRepository.count()).isEqualTo(3L);
        assertThat(notificationRepository.count()).isEqualTo(2L);
        assertThat(userMentionRepository.countByPostIdAndMentionedUserId(postId, actor.getId()))
                .isEqualTo(1L);
        assertMentionNotification(firstTarget, actor, postId, null);
        assertMentionNotification(secondTarget, actor, postId, null);
        assertNotificationCount(actor, 0);
    }

    @Test
    void multipartPostUsesTheSameMentionFlow() throws Exception {
        User actor = saveUser("actor");
        User target = saveUser("target");
        String content = "clip for @" + target.getHandle();

        MvcResult result = mockMvc.perform(multipart("/api/v1/posts")
                        .param("content", content)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(content))
                .andReturn();
        UUID postId = UUID.fromString(body(result).path("data").path("postId").asText());

        assertThat(userMentionRepository.count()).isEqualTo(1L);
        assertMentionNotification(target, actor, postId, null);
    }

    @Test
    void missingDeletedAndWrongCaseHandlesRemainPlainTextWithoutFailingPost() throws Exception {
        User actor = saveUser("actor");
        User deletedTarget = saveUser("target");
        ReflectionTestUtils.setField(deletedTarget, "deletedAt", OffsetDateTime.now());
        userRepository.saveAndFlush(deletedTarget);
        String wrongCase = deletedTarget.getHandle().substring(0, 1).toUpperCase()
                + deletedTarget.getHandle().substring(1);
        String content = "@missing @" + deletedTarget.getHandle() + " @" + wrongCase;

        mockMvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(content));

        assertThat(userMentionRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void commentKeepsPostAuthorCommentNotificationAndMentionsOnlyOtherUsers() throws Exception {
        User author = saveUser("author");
        User actor = saveUser("actor");
        User otherTarget = saveUser("other");
        Post post = postRepository.saveAndFlush(Post.create(author, "post"));
        String content = "@" + author.getHandle() + " check with @" + otherTarget.getHandle();

        MvcResult result = mockMvc.perform(post("/api/v1/posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(body(result).path("data").path("commentId").asText());

        assertThat(userMentionRepository.count()).isEqualTo(2L);
        assertThat(notificationRepository.count()).isEqualTo(2L);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("comment"))
                .andExpect(jsonPath("$.data.items[0].commentId").value(commentId.toString()));
        assertMentionNotification(otherTarget, actor, post.getId(), commentId);
    }

    @Test
    void deletingCommentRemovesItsMentionRelationsAndAllRelatedNotifications() throws Exception {
        User author = saveUser("author");
        User actor = saveUser("actor");
        User otherTarget = saveUser("other");
        Post post = postRepository.saveAndFlush(Post.create(author, "post"));

        MvcResult result = mockMvc.perform(post("/api/v1/posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "@" + otherTarget.getHandle()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(body(result).path("data").path("commentId").asText());

        mockMvc.perform(delete("/api/v1/posts/{postId}/comments/{commentId}", post.getId(), commentId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor)))
                .andExpect(status().isOk());

        assertThat(userMentionRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
        assertThat(postCommentRepository.findById(commentId)).isEmpty();
    }

    @Test
    void softDeletedCommentKeepsMentionDataButHidesMentionNotification() throws Exception {
        User author = saveUser("author");
        User actor = saveUser("actor");
        User target = saveUser("target");
        Post post = postRepository.saveAndFlush(Post.create(author, "post"));

        MvcResult result = mockMvc.perform(post("/api/v1/posts/{postId}/comments", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "@" + target.getHandle()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID commentId = UUID.fromString(body(result).path("data").path("commentId").asText());
        var comment = postCommentRepository.findById(commentId).orElseThrow();
        comment.softDelete();
        postCommentRepository.saveAndFlush(comment);

        assertThat(userMentionRepository.count()).isEqualTo(1L);
        assertThat(notificationRepository.count()).isEqualTo(2L);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void softDeletedPostKeepsMentionDataButHidesNotificationAndUnreadCount() throws Exception {
        User actor = saveUser("actor");
        User target = saveUser("target");

        MvcResult result = mockMvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hello @" + target.getHandle()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID postId = UUID.fromString(body(result).path("data").path("postId").asText());

        mockMvc.perform(delete("/api/v1/posts/{postId}", postId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(actor)))
                .andExpect(status().isOk());

        assertThat(userMentionRepository.count()).isEqualTo(1L);
        assertThat(notificationRepository.count()).isEqualTo(1L);
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    private void assertMentionNotification(
            User recipient,
            User actor,
            UUID postId,
            UUID commentId
    ) throws Exception {
        var request = mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(recipient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("mention"))
                .andExpect(jsonPath("$.data.items[0].actor.userId").value(actor.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].postId").value(postId.toString()));
        if (commentId == null) {
            request.andExpect(jsonPath("$.data.items[0].commentId").value(nullValue()));
        } else {
            request.andExpect(jsonPath("$.data.items[0].commentId").value(commentId.toString()));
        }
    }

    private void assertNotificationCount(User user, int expected) throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(expected)));
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
