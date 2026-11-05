package com.gamerin.backend.domain.hashtag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

@SpringBootTest
@AutoConfigureMockMvc
class HashtagControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
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
    void hashtagEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/hashtags").param("query", "pubg"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "pubg"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void jsonAndMultipartPostCreationPersistHashtagRelations() throws Exception {
        User user = saveUser("writer");
        String token = bearerToken(user);

        mockMvc.perform(post("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("content", "JSON 글 #PUBG #pubg")
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/posts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("content", "multipart 글 #배그"))
                .andExpect(status().isOk());

        assertThat(hashtagRepository.findAll())
                .extracting(com.gamerin.backend.domain.hashtag.entity.Hashtag::getNormalizedName)
                .containsExactlyInAnyOrder("PUBG", "pubg", "배그");
        assertThat(postHashtagRepository.count()).isEqualTo(3);
    }

    @Test
    void autocompleteAndPostLookupAreCaseSensitive() throws Exception {
        User user = saveUser("viewer");
        attach(savePost(user, "대문자 글 #PUBG #배그"));
        attach(savePost(user, "소문자 글 #pubg"));
        String token = bearerToken(user);

        mockMvc.perform(get("/api/v1/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("query", "PU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("PUBG"))
                .andExpect(jsonPath("$.data[0].postCount").value(1));

        mockMvc.perform(get("/api/v1/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("query", "pu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("pubg"))
                .andExpect(jsonPath("$.data[0].postCount").value(1));

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "pubg")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("소문자 글 #pubg"))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "PUBG")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("대문자 글 #PUBG #배그"));
    }

    @Test
    void softDeletedPostsAreExcludedFromSuggestionsAndResults() throws Exception {
        User user = saveUser("deleted");
        Post post = savePost(user, "숨겨질 글 #hidden");
        attach(post);
        post.softDelete();
        postRepository.saveAndFlush(post);
        String token = bearerToken(user);

        mockMvc.perform(get("/api/v1/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("query", "hid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "hidden")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void postLookupProvidesStableCursorPagination() throws Exception {
        User user = saveUser("cursor");
        attach(savePost(user, "오래된 글 #ranked"));
        attach(savePost(user, "최신 글 #ranked"));
        String token = bearerToken(user);

        MvcResult firstPage = mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "ranked")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("최신 글 #ranked"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn();
        String cursor = body(firstPage).path("data").path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "ranked")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].content").value("오래된 글 #ranked"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void invalidLookupAndCursorReturnBadRequest() throws Exception {
        User user = saveUser("invalid");
        String token = bearerToken(user);

        mockMvc.perform(get("/api/v1/hashtags")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("query", "tag-name"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/hashtags/{name}/posts", "tag")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void attach(Post post) {
        hashtagService.attachToPost(post);
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
