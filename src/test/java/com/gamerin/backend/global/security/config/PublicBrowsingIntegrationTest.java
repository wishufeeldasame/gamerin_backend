package com.gamerin.backend.global.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.mentoring.entity.*;
import com.gamerin.backend.domain.post.entity.*;
import com.gamerin.backend.domain.repost.entity.PostRepost;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicBrowsingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper mapper;

    User author;
    Post post;
    MentorProfile mentor;
    MentoringProgram program;

    @BeforeEach
    void seed() {
        author = saveUser("publicauthor");
        post = Post.create(author, "public post");
        em.persist(post);
        em.persist(PostComment.create(post, author, "public comment"));
        em.persist(PostMedia.create(post, PostMediaType.IMAGE, "/uploads/public.jpg", null, 0));
        mentor = new MentorProfile();
        mentor.setUser(author);
        mentor.setStatus(MentorStatus.ACTIVE);
        em.persist(mentor);
        program = new MentoringProgram();
        program.setMentor(mentor);
        program.setTitle("public coaching");
        program.setContent("coaching details");
        program.setGameName("LOL");
        program.setPrice(1000L);
        em.persist(program);
        em.flush();
    }

    @Test
    void anonymousCanReadEveryAllowedResourceWithoutPrivateStateOrViewWrites() throws Exception {
        for (String path : List.of("/feed", "/feed?tab=all", "/posts/" + post.getId(),
                "/posts/by-number/" + post.getPostNumber(), "/posts/" + post.getId() + "/comments",
                "/users/publicauthor", "/users/publicauthor/posts", "/users/publicauthor/media",
                "/mentoring/programs", "/mentoring/programs/" + program.getId(),
                "/mentoring/mentors/" + author.getId(),
                "/mentoring/mentors/" + author.getId() + "/reviews", "/games")) {
            mvc.perform(get("/api/v1" + path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        for (String path : List.of("/posts/" + post.getId(), "/posts/by-number/" + post.getPostNumber())) {
            mvc.perform(get("/api/v1" + path))
                    .andExpect(jsonPath("$.data.likedByMe").value(false))
                    .andExpect(jsonPath("$.data.bookmarkedByMe").value(false))
                    .andExpect(jsonPath("$.data.isReposted").value(false))
                    .andExpect(jsonPath("$.data.mine").value(false))
                    .andExpect(jsonPath("$.data.editableUntil").isEmpty())
                    .andExpect(jsonPath("$.data.editCount").isEmpty());
        }
        mvc.perform(get("/api/v1/posts/{id}/comments", post.getId()))
                .andExpect(jsonPath("$.data[0].mine").value(false));
        mvc.perform(get("/api/v1/users/publicauthor"))
                .andExpect(jsonPath("$.data.isFollowing").value(false))
                .andExpect(jsonPath("$.data.followsViewer").value(false))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.mileageBalance").doesNotExist());
        assertThat(em.createQuery("select count(v) from PostView v", Long.class).getSingleResult()).isZero();
        assertThat(post.getViewCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"following", " FOLLOWING ", "Following"})
    void followingRequiresAuthenticationEvenWithCursorAndNormalizedTab(String tab) throws Exception {
        unauthorized(get("/api/v1/feed").param("tab", tab).param("cursor", "unused"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/me", "/users/me/posts", "/users/me/media", "/users/me/bookmarks",
            "/users/me/settings", "/mentoring/mentors/me", "/mentoring/mentors/me/reviews",
            "/mentoring/applications/mentee", "/mentoring/applications/mentor", "/messages",
            "/messages/recipients", "/wallet", "/payments", "/notifications", "/search",
            "/hashtags", "/users/publicauthor/followers", "/users/publicauthor/following",
            "/posts/video-jobs/00000000-0000-0000-0000-000000000001", "/admin/users"})
    void personalAndNonAllowlistedGetsStayProtected(String path) throws Exception {
        unauthorized(get("/api/v1" + path));
    }

    @ParameterizedTest
    @CsvSource({"POST,/posts", "POST,/posts/{id}/comments", "POST,/posts/{id}/likes",
            "DELETE,/posts/{id}/likes", "POST,/posts/{id}/reposts", "DELETE,/posts/{id}/reposts",
            "POST,/posts/{id}/bookmarks", "DELETE,/posts/{id}/bookmarks", "POST,/posts/{id}/shares",
            "POST,/posts/views", "POST,/users/{id}/follow", "DELETE,/users/{id}/follow",
            "POST,/reports", "POST,/messages", "POST,/mentoring/mentors", "POST,/mentoring/programs",
            "POST,/mentoring/applications", "POST,/mentoring/reviews", "PATCH,/mentoring/programs/{id}",
            "DELETE,/mentoring/programs/{id}", "PATCH,/mentoring/applications/{id}/accept",
            "PATCH,/mentoring/applications/{id}/reject", "PATCH,/mentoring/applications/{id}/cancel",
            "PATCH,/mentoring/applications/{id}/start", "PATCH,/mentoring/applications/{id}/finish",
            "PATCH,/mentoring/applications/{id}/complete", "PATCH,/posts/{id}", "DELETE,/posts/{id}",
            "DELETE,/posts/{id}/comments/{id}"})
    void anonymousMutationsAreRejectedBeforeDomainEffects(String method, String path) throws Exception {
        unauthorized(request(HttpMethod.valueOf(method), "/api/v1" + path.replace("{id}", post.getId().toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(em.createQuery("select count(p) from Post p", Long.class).getSingleResult()).isEqualTo(1);
        assertThat(em.createQuery("select count(a) from MentoringApplication a", Long.class).getSingleResult()).isZero();
        assertThat(em.createQuery("select count(n) from Notification n", Long.class).getSingleResult()).isZero();
    }

    @Test
    void multipartUploadAndInvalidTokenCannotBypassAuthentication() throws Exception {
        unauthorized(multipart("/api/v1/posts").file(new MockMultipartFile("files", "image.jpg", "image/jpeg", new byte[]{1})));
        unauthorized(get("/api/v1/users/me").header("Authorization", "Bearer invalid"));
        mvc.perform(get("/api/v1/posts/{id}", post.getId()).header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk());
    }

    @Test
    void suspendedAuthenticatedUserStaysForbiddenOnPublicResources() throws Exception {
        author.suspend();
        mvc.perform(get("/api/v1/posts/{id}", post.getId()).with(user(CustomUserPrincipal.from(author))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deletedAuthorIsAbsentFromDetailCommentsProfileAndMedia() throws Exception {
        author.withdraw();
        em.flush();
        for (String path : List.of("/posts/" + post.getId(), "/posts/by-number/" + post.getPostNumber(),
                "/posts/" + post.getId() + "/comments", "/users/publicauthor", "/users/publicauthor/posts",
                "/users/publicauthor/media")) {
            mvc.perform(get("/api/v1" + path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/feed")).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void guestTimelineCursorKeepsRepostsUniqueAndMediaAccessible() throws Exception {
        Post second = Post.create(author, "second");
        em.persist(second);
        em.persist(PostRepost.create(post, saveUser("reposter")));
        em.flush();
        JsonNode first = data("/api/v1/feed?size=1");
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asText();
        JsonNode next = mapper.readTree(mvc.perform(get("/api/v1/feed").param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(new HashSet<>(List.of(first.path("items").get(0).path("postId").asText(),
                next.path("items").get(0).path("postId").asText()))).hasSize(2);
        assertThat(next.path("hasNext").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/users/publicauthor/media")).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/users/publicauthor/posts")).andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void hiddenPostsAndCommentsFromDeletedAuthorsDoNotLeak() throws Exception {
        User commenter = saveUser("gonecommenter");
        em.persist(PostComment.create(post, commenter, "withdrawn comment"));
        commenter.withdraw();
        em.flush();
        mvc.perform(get("/api/v1/posts/{id}/comments", post.getId()))
                .andExpect(jsonPath("$.data.length()").value(1));
        post.softDelete();
        em.flush();
        for (String path : List.of("/posts/" + post.getId(), "/posts/by-number/" + post.getPostNumber(),
                "/posts/" + post.getId() + "/comments")) {
            mvc.perform(get("/api/v1" + path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/feed")).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/users/publicauthor/media")).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void publicReviewsOmitApplicationIdsAndHiddenProgramsAndDeletedWriters() throws Exception {
        MentoringReview review = saveReview();
        mvc.perform(get("/api/v1/mentoring/mentors/{id}/reviews", author.getId()))
                .andExpect(jsonPath("$.data.content[0].id").value(review.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].applicationId").doesNotExist());
        review.getMentee().withdraw();
        em.flush();
        mvc.perform(get("/api/v1/mentoring/mentors/{id}/reviews", author.getId()))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void closedProgramsRemainPublicButAdminHiddenProgramsAndReviewsDoNot() throws Exception {
        saveReview();
        program.setStatus(ProgramStatus.CLOSED);
        mvc.perform(get("/api/v1/mentoring/programs/{id}", program.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        program.setAdminSuspendedAt(OffsetDateTime.now());
        em.flush();
        mvc.perform(get("/api/v1/mentoring/programs/{id}", program.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/mentoring/programs")).andExpect(jsonPath("$.data.content").isEmpty());
        mvc.perform(get("/api/v1/mentoring/mentors/{id}/reviews", author.getId()))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_APPROVAL", "INACTIVE", "SUSPENDED", "DELETED"})
    void unavailableMentorsAreNotPublic(String state) throws Exception {
        if (state.equals("SUSPENDED")) author.suspend();
        else if (state.equals("DELETED")) author.withdraw();
        else mentor.setStatus(MentorStatus.valueOf(state));
        em.flush();
        mvc.perform(get("/api/v1/mentoring/programs")).andExpect(jsonPath("$.data.content").isEmpty());
        for (String path : List.of("/programs/" + program.getId(), "/mentors/" + author.getId(),
                "/mentors/" + author.getId() + "/reviews")) {
            mvc.perform(get("/api/v1/mentoring" + path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void publicMentoringPaginationHasRealSizeAndSortLimits() throws Exception {
        for (String path : List.of("/programs", "/mentors/" + author.getId() + "/reviews")) {
            mvc.perform(get("/api/v1/mentoring" + path).param("size", "999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(50));
            mvc.perform(get("/api/v1/mentoring" + path).param("sort", "mentor.user.email,desc"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void openApiDescribesPublicReadsAndProtectedWrites() throws Exception {
        JsonNode paths = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("paths");
        for (String path : List.of("/api/v1/feed", "/api/v1/posts/{postId}",
                "/api/v1/posts/by-number/{postNumber}", "/api/v1/posts/{postId}/comments",
                "/api/v1/users/{handle}", "/api/v1/users/{handle}/posts", "/api/v1/users/{handle}/media",
                "/api/v1/mentoring/programs", "/api/v1/mentoring/programs/{id}",
                "/api/v1/mentoring/mentors/{mentorId}", "/api/v1/mentoring/mentors/{mentorId}/reviews", "/api/v1/games")) {
            assertThat(paths.path(path).path("get").isMissingNode()).as(path).isFalse();
            assertThat(paths.path(path).path("get").path("security").isMissingNode()
                    || paths.path(path).path("get").path("security").isEmpty()).isTrue();
        }
        assertThat(paths.path("/api/v1/posts").path("post").path("security").isEmpty()).isFalse();
    }

    private void unauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false));
    }

    private JsonNode data(String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private User saveUser(String handle) {
        User u = User.createLocal(handle + "@example.com", handle, handle, "encoded");
        u.setProfile(UserProfile.createDefault(u));
        em.persist(u);
        return u;
    }

    private MentoringReview saveReview() {
        MentoringApplication a = new MentoringApplication();
        a.setProgram(program);
        a.setMentee(saveUser("reviewer"));
        a.setAppliedMileage(1000L);
        a.setStatus(ApplicationStatus.COMPLETED);
        em.persist(a);
        MentoringReview r = new MentoringReview();
        r.setApplication(a);
        r.setMentor(mentor);
        r.setMentee(a.getMentee());
        r.setRating(5);
        r.setContent("helpful");
        em.persist(r);
        em.flush();
        return r;
    }
}
