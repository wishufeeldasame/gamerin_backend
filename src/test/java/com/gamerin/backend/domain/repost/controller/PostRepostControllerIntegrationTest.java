package com.gamerin.backend.domain.repost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.repost.entity.PostRepost;
import com.gamerin.backend.domain.repost.repository.PostRepostRepository;
import com.gamerin.backend.domain.repost.service.PostRepostService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
class PostRepostControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostRepostRepository postRepostRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PostRepostService postRepostService;

    @BeforeEach
    @AfterEach
    void cleanRepostFixtures() {
        postRepostRepository.deleteAllInBatch();
        followRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void repostEndpointsRequireAuthentication() throws Exception {
        UUID postId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/posts/{postId}/reposts", postId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(delete("/api/v1/posts/{postId}/reposts", postId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void repostAndUnrepostAreIdempotentAndUpdatePostMetrics() throws Exception {
        User author = saveUser("author");
        User viewer = saveUser("viewer");
        Post target = savePost(author, "target post");
        String token = bearerToken(viewer);

        MvcResult firstResult = mockMvc.perform(post("/api/v1/posts/{postId}/reposts", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.postId").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.isReposted").value(true))
                .andExpect(jsonPath("$.data.repostCount").value(1))
                .andExpect(jsonPath("$.data.repostedAt").isString())
                .andReturn();
        String firstRepostedAt = body(firstResult).path("data").path("repostedAt").asText();

        MvcResult repeatedResult = mockMvc.perform(post("/api/v1/posts/{postId}/reposts", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isReposted").value(true))
                .andExpect(jsonPath("$.data.repostCount").value(1))
                .andReturn();
        assertThat(body(repeatedResult).path("data").path("repostedAt").asText())
                .isEqualTo(firstRepostedAt);
        assertThat(postRepostRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/posts/{postId}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isReposted").value(true))
                .andExpect(jsonPath("$.data.repostCount").value(1))
                .andExpect(jsonPath("$.data.reposterInfo").value(nullValue()));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/v1/posts/{postId}/reposts", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isReposted").value(false))
                    .andExpect(jsonPath("$.data.repostCount").value(0))
                    .andExpect(jsonPath("$.data.repostedAt").value(nullValue()));
        }
        assertThat(postRepostRepository.count()).isZero();

        mockMvc.perform(get("/api/v1/posts/{postId}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isReposted").value(false))
                .andExpect(jsonPath("$.data.repostCount").value(0));

        MvcResult recreatedResult = mockMvc.perform(post("/api/v1/posts/{postId}/reposts", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isReposted").value(true))
                .andExpect(jsonPath("$.data.repostCount").value(1))
                .andReturn();
        OffsetDateTime recreatedAt = OffsetDateTime.parse(
                body(recreatedResult).path("data").path("repostedAt").asText()
        );
        assertThat(recreatedAt).isAfter(OffsetDateTime.parse(firstRepostedAt));
    }

    @Test
    void repostRejectsOwnDeletedAndUnknownPosts() throws Exception {
        User author = saveUser("owner");
        User viewer = saveUser("other");
        Post ownPost = savePost(author, "own post");
        Post deletedPost = savePost(author, "deleted post");
        deletedPost.softDelete();
        postRepository.saveAndFlush(deletedPost);

        mockMvc.perform(post("/api/v1/posts/{postId}/reposts", ownPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(author)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/v1/posts/{postId}/reposts", deletedPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(delete("/api/v1/posts/{postId}/reposts", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void feedsAndProfilesUseLatestEligibleRepostWithoutDuplicatingTheOriginal() throws Exception {
        User viewer = saveUser("viewer");
        User author = saveUser("author");
        User first = saveUser("first");
        User second = saveUser("second");
        User outsider = saveUser("outsider");
        followRepository.saveAndFlush(Follow.create(viewer, first));
        followRepository.saveAndFlush(Follow.create(viewer, second));

        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        Post oldPost = savePost(author, "old original");
        Post recentPost = savePost(first, "recent original");
        setPostTime(oldPost.getId(), base.plusHours(1));
        setPostTime(recentPost.getId(), base.plusHours(3));

        PostRepost firstRepost = saveRepost(oldPost, first);
        PostRepost secondRepost = saveRepost(oldPost, second);
        PostRepost outsiderRepost = saveRepost(oldPost, outsider);
        setRepostTime(firstRepost.getId(), base.plusHours(4));
        setRepostTime(secondRepost.getId(), base.plusHours(5));
        setRepostTime(outsiderRepost.getId(), base.plusHours(6));

        String viewerToken = bearerToken(viewer);
        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, viewerToken)
                        .param("tab", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].repostCount").value(3))
                .andExpect(jsonPath("$.data.items[0].isReposted").value(false))
                .andExpect(jsonPath("$.data.items[0].reposterInfo.userId").value(outsider.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].reposterInfo.nickname").value(outsider.getNickname()))
                .andExpect(jsonPath("$.data.items[0].reposterInfo.repostedAt").isString())
                .andExpect(jsonPath("$.data.items[1].postId").value(recentPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].reposterInfo").value(nullValue()));

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, viewerToken)
                        .param("tab", "following"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].reposterInfo.userId").value(second.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].postId").value(recentPost.getId().toString()));

        mockMvc.perform(get("/api/v1/users/{handle}/posts", first.getHandle())
                        .header(HttpHeaders.AUTHORIZATION, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].reposterInfo.userId").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].postId").value(recentPost.getId().toString()));

        mockMvc.perform(get("/api/v1/users/{handle}/posts", author.getHandle())
                        .header(HttpHeaders.AUTHORIZATION, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldPost.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].reposterInfo").value(nullValue()));
    }

    @Test
    void timelineCursorIsStableAndRejectsMalformedValues() throws Exception {
        User viewer = saveUser("cursor");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        Post newest = savePost(viewer, "newest");
        Post middle = savePost(viewer, "middle");
        Post oldest = savePost(viewer, "oldest");
        setPostTime(newest.getId(), base.plusHours(3));
        setPostTime(middle.getId(), base.plusHours(2));
        setPostTime(oldest.getId(), base.plusHours(1));
        String token = bearerToken(viewer);

        MvcResult firstPageResult = mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].postId").value(newest.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].postId").value(middle.getId().toString()))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isString())
                .andReturn();
        JsonNode firstPage = body(firstPageResult).path("data");
        String cursor = firstPage.path("nextCursor").asText();

        Post insertedAfterSnapshot = savePost(viewer, "inserted later");
        MvcResult secondPageResult = mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldest.getId().toString()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn();

        Set<UUID> seen = new HashSet<>();
        firstPage.path("items").forEach(item -> seen.add(UUID.fromString(item.path("postId").asText())));
        body(secondPageResult).path("data").path("items")
                .forEach(item -> seen.add(UUID.fromString(item.path("postId").asText())));
        assertThat(seen).containsExactlyInAnyOrder(newest.getId(), middle.getId(), oldest.getId());
        assertThat(seen).doesNotContain(insertedAfterSnapshot.getId());

        String legacyCursor = base.plusHours(2) + "|" + middle.getId();
        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "2")
                        .param("cursor", legacyCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(oldest.getId().toString()));

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void softDeletedRepostersAreExcludedFromMetricsAndTimelineContext() throws Exception {
        User author = saveUser("active-author");
        User reposter = saveUser("deleted-reposter");
        User viewer = saveUser("active-viewer");
        Post target = savePost(author, "visible original");
        saveRepost(target, reposter);
        jdbcTemplate.update(
                "update users set deleted_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC),
                reposter.getId()
        );
        String token = bearerToken(viewer);

        mockMvc.perform(get("/api/v1/posts/{postId}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repostCount").value(0))
                .andExpect(jsonPath("$.data.isReposted").value(false));

        mockMvc.perform(get("/api/v1/feed")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("tab", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(target.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].repostCount").value(0))
                .andExpect(jsonPath("$.data.items[0].reposterInfo").value(nullValue()));
    }

    @Test
    void concurrentRepeatedRepostsCreateOnlyOneRelation() throws Exception {
        User author = saveUser("concurrent-author");
        User reposter = saveUser("concurrent-reposter");
        Post target = savePost(author, "concurrent target");
        CustomUserPrincipal principal = CustomUserPrincipal.from(reposter);
        int requestCount = 6;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<OffsetDateTime>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return postRepostService.repost(principal, target.getId()).repostedAt();
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Set<OffsetDateTime> repostedTimes = new HashSet<>();
            for (Future<OffsetDateTime> future : futures) {
                repostedTimes.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(repostedTimes).hasSize(1);
            assertThat(postRepostRepository.count()).isEqualTo(1);
            assertThat(postRepostRepository.countActiveByPostId(target.getId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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

    private PostRepost saveRepost(Post post, User user) {
        return postRepostRepository.saveAndFlush(PostRepost.create(post, user));
    }

    private void setPostTime(UUID postId, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "update posts set created_at = ?, updated_at = ? where id = ?",
                createdAt,
                createdAt,
                postId
        );
    }

    private void setRepostTime(UUID repostId, OffsetDateTime repostedAt) {
        jdbcTemplate.update(
                "update post_reposts set reposted_at = ? where id = ?",
                repostedAt,
                repostId
        );
    }

    private String bearerToken(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
        return "Bearer " + accessToken;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
