package com.gamerin.backend.domain.post.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class PostViewControllerIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from post_views");
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void viewsEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/posts/views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("postIds", List.of(UUID.randomUUID())))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewsEndpointRejectsEmptyOversizedAndNullIdLists() throws Exception {
        String token = bearerToken(saveUser("reader"));
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        List<UUID> tooMany = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList();

        for (Object postIds : List.of(List.of(), tooMany, withNull)) {
            mockMvc.perform(post("/api/v1/posts/views")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postIds", postIds))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        mockMvc.perform(post("/api/v1/posts/views")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/posts/views")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postIds\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewsEndpointCountsOncePer24HoursAndDetailSharesTheSameWindow() throws Exception {
        User author = saveUser("author");
        Post target = postRepository.saveAndFlush(Post.create(author, "hello"));
        String token = bearerToken(saveUser("reader"));
        String body = json(Map.of("postIds", List.of(target.getId())));

        mockMvc.perform(post("/api/v1/posts/views").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counted").value(1));
        mockMvc.perform(post("/api/v1/posts/views").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.counted").value(0));

        // 카드 노출로 이미 센 글은 상세 진입으로 다시 세지 않는다.
        mockMvc.perform(get("/api/v1/posts/{id}", target.getId()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/api/v1/posts/by-number/{n}", target.getPostNumber())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.data.viewCount").value(1));
    }

    @Test
    void detailEntryCountsAndAuthorViewIsIncluded() throws Exception {
        User author = saveUser("author");
        Post target = postRepository.saveAndFlush(Post.create(author, "hello"));
        String authorToken = bearerToken(author);

        // 응답은 증가 전 값(0), 다음 조회부터 반영된다.
        mockMvc.perform(get("/api/v1/posts/{id}", target.getId()).header(HttpHeaders.AUTHORIZATION, authorToken))
                .andExpect(jsonPath("$.data.viewCount").value(0));
        mockMvc.perform(get("/api/v1/posts/by-number/{n}", target.getPostNumber())
                        .header(HttpHeaders.AUTHORIZATION, authorToken))
                .andExpect(jsonPath("$.data.viewCount").value(1));

        mockMvc.perform(get("/api/v1/posts/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(saveUser("other"))))
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/api/v1/posts/{id}", target.getId()).header(HttpHeaders.AUTHORIZATION, authorToken))
                .andExpect(jsonPath("$.data.viewCount").value(2));
    }

    @Test
    void deletedPostDetailIs404AndNotCounted() throws Exception {
        Post target = postRepository.saveAndFlush(Post.create(saveUser("author"), "hello"));
        target.softDelete();
        postRepository.saveAndFlush(target);
        String token = bearerToken(saveUser("reader"));

        mockMvc.perform(get("/api/v1/posts/{id}", target.getId()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/posts/views").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("postIds", List.of(target.getId())))))
                .andExpect(jsonPath("$.data.counted").value(0));
    }

    private User saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = User.createLocal(prefix + "+" + suffix + "@example.com", prefix + suffix, prefix, "encoded-password");
        user.setProfile(UserProfile.createDefault(user));
        return userRepository.saveAndFlush(user);
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(user.getId(), user.getHandle(), List.of("ROLE_USER"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
