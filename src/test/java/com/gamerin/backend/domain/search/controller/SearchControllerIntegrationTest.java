package com.gamerin.backend.domain.search.controller;

import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.hashtag.repository.HashtagRepository;
import com.gamerin.backend.domain.hashtag.repository.PostHashtagRepository;
import com.gamerin.backend.domain.hashtag.service.HashtagService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
class SearchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private HashtagService hashtagService;
    @Autowired
    private PostHashtagRepository postHashtagRepository;
    @Autowired
    private HashtagRepository hashtagRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        postHashtagRepository.deleteAllInBatch();
        hashtagRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void allSearchEndpointsRequireAuthentication() throws Exception {
        List<String> paths = List.of(
                "/api/v1/search",
                "/api/v1/search/accounts",
                "/api/v1/search/posts",
                "/api/v1/search/hashtags"
        );

        for (String path : paths) {
            mockMvc.perform(get(path).param("q", "ranked"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Test
    void overviewReturnsAccountsPostsAndHashtagsAndExcludesDeletedPosts() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        saveUser("rankplayer", "Rank Player");
        attach(savePost(viewer, "활성 Ranked 기록 #Ranked"));
        Post deleted = savePost(viewer, "삭제 Ranked 기록 #Ranked");
        attach(deleted);
        deleted.softDelete();
        postRepository.saveAndFlush(deleted);

        mockMvc.perform(get("/api/v1/search")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(viewer))
                        .param("q", "Rank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("Rank"))
                .andExpect(jsonPath("$.data.accounts.items", hasSize(1)))
                .andExpect(jsonPath("$.data.accounts.items[0].handle").value("rankplayer"))
                .andExpect(jsonPath("$.data.posts.items", hasSize(1)))
                .andExpect(jsonPath("$.data.posts.items[0].content").value("활성 Ranked 기록 #Ranked"))
                .andExpect(jsonPath("$.data.hashtags.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hashtags.items[0].name").value("Ranked"))
                .andExpect(jsonPath("$.data.hashtags.items[0].postCount").value(1));
    }

    @Test
    void accountSearchUsesStableAlphabeticalCursor() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        saveUser("a_accountneedle", "Second");
        saveUser("b_accountneedle", "First");
        String token = bearerToken(viewer);

        MvcResult firstPage = mockMvc.perform(get("/api/v1/search/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "accountneedle")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].handle").value("a_accountneedle"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn();
        String cursor = body(firstPage).path("data").path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/search/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "accountneedle")
                        .param("size", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].handle").value("b_accountneedle"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void accountSearchIsCaseSensitive() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        saveUser("upper_result", "PlayerNeedle");
        saveUser("lower_result", "playerneedle");
        String token = bearerToken(viewer);

        mockMvc.perform(get("/api/v1/search/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "PlayerNeedle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].handle").value("upper_result"));

        mockMvc.perform(get("/api/v1/search/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "playerneedle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].handle").value("lower_result"));
    }

    @Test
    void postSearchIsCaseSensitiveAndUsesLatestFirstCursor() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        Post older = savePost(viewer, "오래된 SearchNeedle 기록");
        Post latest = savePost(viewer, "최신 SearchNeedle 기록");
        savePost(viewer, "제외 searchneedle 기록");
        setPostTime(older.getId(), OffsetDateTime.parse("2026-08-06T10:00:00+09:00"));
        setPostTime(latest.getId(), OffsetDateTime.parse("2026-08-06T10:01:00+09:00"));
        entityManager.clear();
        String token = bearerToken(viewer);

        MvcResult firstPage = mockMvc.perform(get("/api/v1/search/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "SearchNeedle")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("최신 SearchNeedle 기록"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn();
        String cursor = body(firstPage).path("data").path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/search/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "SearchNeedle")
                        .param("size", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("오래된 SearchNeedle 기록"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void hashtagSearchUsesPrefixAndLiteralWildcardsAreEscaped() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        attach(savePost(viewer, "랭크 #PUBG_Ranked"));
        savePost(viewer, "승률 100% 기록");
        savePost(viewer, "일반 기록");
        String token = bearerToken(viewer);

        mockMvc.perform(get("/api/v1/search/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "PUBG_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("PUBG_Ranked"));

        mockMvc.perform(get("/api/v1/search/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "pubg_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/search/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("승률 100% 기록"));
    }

    @Test
    void invalidQueriesAndCursorsReturnBadRequest() throws Exception {
        User viewer = saveUser("viewer", "Viewer");
        String token = bearerToken(viewer);

        mockMvc.perform(get("/api/v1/search")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/search/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "viewer")
                        .param("cursor", "invalid"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "post")
                        .param("cursor", "invalid"))
                .andExpect(status().isBadRequest());
    }

    private void attach(Post post) {
        hashtagService.attachToPost(post);
    }

    private User saveUser(String handle, String nickname) {
        User user = User.createLocal(
                handle + "+" + UUID.randomUUID() + "@example.com",
                handle,
                nickname,
                "encoded-password"
        );
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private Post savePost(User author, String content) {
        return postRepository.saveAndFlush(Post.create(author, content));
    }

    private void setPostTime(UUID postId, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "update posts set created_at = ?, updated_at = ? where id = ?",
                createdAt,
                createdAt,
                postId
        );
    }

    private String bearerToken(User user) {
        String token = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
        return "Bearer " + token;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
