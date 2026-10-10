package com.gamerin.backend.domain.bookmark.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionItemRepository;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionRepository;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
class BookmarkCollectionControllerIntegrationTest {

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
    private PostBookmarkRepository postBookmarkRepository;
    @Autowired
    private BookmarkCollectionItemRepository itemRepository;
    @Autowired
    private BookmarkCollectionRepository collectionRepository;

    @BeforeEach
    @AfterEach
    void cleanBookmarkFixtures() {
        itemRepository.deleteAllInBatch();
        collectionRepository.deleteAllInBatch();
        postBookmarkRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        userRepository.deleteAll();
        userRepository.flush();
    }

    @Test
    void allCollectionEndpointsRequireAuthentication() throws Exception {
        UUID collectionId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        List<RequestBuilder> requests = List.of(
                post("/api/v1/bookmark-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Clips"))),
                get("/api/v1/bookmark-collections"),
                get("/api/v1/bookmark-collections/{collectionId}", collectionId),
                patch("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ranked"))),
                delete("/api/v1/bookmark-collections/{collectionId}", collectionId),
                get("/api/v1/bookmark-collections/{collectionId}/bookmarks", collectionId),
                put("/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}", collectionId, postId),
                delete("/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}", collectionId, postId)
        );

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Test
    void createListGetRenameAndDeleteFollowTheHttpContract() throws Exception {
        User user = saveUser("contract");
        String token = bearerToken(user);

        MvcResult createResult = mockMvc.perform(post("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Clips  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Clips"))
                .andExpect(jsonPath("$.data.coverImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.bookmarkCount").value(0))
                .andExpect(jsonPath("$.data.containsPost").value(false))
                .andReturn();
        UUID collectionId = dataUuid(createResult, "collectionId");

        mockMvc.perform(get("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].collectionId").value(collectionId.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Clips"));

        mockMvc.perform(get("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionId").value(collectionId.toString()))
                .andExpect(jsonPath("$.data.name").value("Clips"));

        mockMvc.perform(patch("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Ranked"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionId").value(collectionId.toString()))
                .andExpect(jsonPath("$.data.name").value("Ranked"));

        mockMvc.perform(delete("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void invalidAndDuplicateNamesReturnThePolicyStatusCodes() throws Exception {
        User user = saveUser("validation");
        String token = bearerToken(user);

        mockMvc.perform(post("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "a".repeat(41)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        createCollection(token, "Clips");
        mockMvc.perform(post("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  cLiPs  "))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void anotherUsersCollectionIsHiddenAsNotFoundForEveryOwnedOperation() throws Exception {
        User owner = saveUser("owner");
        User viewer = saveUser("viewer");
        Post post = savePost(owner, "private collection post");
        UUID collectionId = createCollection(bearerToken(owner), "Private");
        String viewerToken = bearerToken(viewer);

        List<RequestBuilder> requests = List.of(
                get("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, viewerToken),
                patch("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Stolen"))),
                delete("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, viewerToken),
                get("/api/v1/bookmark-collections/{collectionId}/bookmarks", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, viewerToken),
                put("/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}", collectionId, post.getId())
                        .header(HttpHeaders.AUTHORIZATION, viewerToken),
                delete("/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}", collectionId, post.getId())
                        .header(HttpHeaders.AUTHORIZATION, viewerToken)
        );

        for (RequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Test
    void membershipRequestsAreIdempotentAndPreserveTheCanonicalBookmark() throws Exception {
        User user = saveUser("membership");
        Post post = savePost(user, "bookmark policy post");
        String token = bearerToken(user);
        UUID collectionId = createCollection(token, "Clips");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put(
                            "/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}",
                            collectionId,
                            post.getId()
                    )
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.postId").value(post.getId().toString()))
                    .andExpect(jsonPath("$.data.bookmarkedByMe").value(true))
                    .andExpect(jsonPath("$.data.collectionIds", hasSize(1)))
                    .andExpect(jsonPath("$.data.collectionIds[0]").value(collectionId.toString()))
                    .andExpect(jsonPath("$.data.collection.bookmarkCount").value(1))
                    .andExpect(jsonPath("$.data.collection.containsPost").value(true));
        }

        assertThat(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).isTrue();
        assertThat(itemRepository.findCollectionIdsContainingPost(user.getId(), post.getId()))
                .containsExactly(collectionId);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete(
                            "/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}",
                            collectionId,
                            post.getId()
                    )
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bookmarkedByMe").value(true))
                    .andExpect(jsonPath("$.data.collectionIds", hasSize(0)))
                    .andExpect(jsonPath("$.data.collection.bookmarkCount").value(0))
                    .andExpect(jsonPath("$.data.collection.containsPost").value(false));
        }

        assertThat(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).isTrue();

        addPost(token, collectionId, post.getId());
        mockMvc.perform(delete("/api/v1/bookmark-collections/{collectionId}", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        assertThat(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).isTrue();

        UUID nextCollectionId = createCollection(token, "Global removal");
        addPost(token, nextCollectionId, post.getId());
        mockMvc.perform(delete("/api/v1/posts/{postId}/bookmarks", post.getId())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).isFalse();
        assertThat(itemRepository.findCollectionIdsContainingPost(user.getId(), post.getId())).isEmpty();
    }

    @Test
    void collectionBookmarkQueryBindsCursorKeywordSizeAndMediaFilter() throws Exception {
        User user = saveUser("query");
        Post rankedPost = savePost(user, "ranked win rate 100%");
        Post casualPost = savePost(user, "casual match");
        String token = bearerToken(user);
        UUID collectionId = createCollection(token, "Search");
        addPost(token, collectionId, rankedPost.getId());
        addPost(token, collectionId, casualPost.getId());

        MvcResult firstPageResult = mockMvc.perform(get(
                        "/api/v1/bookmark-collections/{collectionId}/bookmarks",
                        collectionId
                )
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isString())
                .andReturn();
        JsonNode firstPage = body(firstPageResult).path("data");
        UUID firstPostId = UUID.fromString(firstPage.path("items").get(0).path("postId").asText());
        String cursor = firstPage.path("nextCursor").asText();

        MvcResult secondPageResult = mockMvc.perform(get(
                        "/api/v1/bookmark-collections/{collectionId}/bookmarks",
                        collectionId
                )
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn();
        UUID secondPostId = UUID.fromString(
                body(secondPageResult).path("data").path("items").get(0).path("postId").asText()
        );
        assertThat(Set.of(firstPostId, secondPostId))
                .isEqualTo(new HashSet<>(List.of(rankedPost.getId(), casualPost.getId())));

        mockMvc.perform(get("/api/v1/bookmark-collections/{collectionId}/bookmarks", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("q", "100%")
                        .param("mediaOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].postId").value(rankedPost.getId().toString()));

        mockMvc.perform(get("/api/v1/bookmark-collections/{collectionId}/bookmarks", collectionId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .param("mediaOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    private UUID createCollection(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/bookmark-collections")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return dataUuid(result, "collectionId");
    }

    private void addPost(String token, UUID collectionId, UUID postId) throws Exception {
        mockMvc.perform(put(
                        "/api/v1/bookmark-collections/{collectionId}/bookmarks/{postId}",
                        collectionId,
                        postId
                )
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
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
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getHandle(),
                List.of("ROLE_USER")
        );
        return "Bearer " + accessToken;
    }

    private UUID dataUuid(MvcResult result, String fieldName) throws Exception {
        return UUID.fromString(body(result).path("data").path(fieldName).asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
