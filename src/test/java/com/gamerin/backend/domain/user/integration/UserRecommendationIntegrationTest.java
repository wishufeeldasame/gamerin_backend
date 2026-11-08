package com.gamerin.backend.domain.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.user.dto.response.UserRecommendationResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.service.UserRecommendationService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import jakarta.persistence.EntityManager;

/** 기본 H2. RECOMMENDATION_POSTGRES_TEST_URL 지정 시 동일 테스트를 Flyway가 적용된 전용 PostgreSQL DB에서 실행한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserRecommendationIntegrationTest {

    private static final String PATH = "/api/v1/users/me/recommendations";
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserRecommendationService service;
    @Autowired FollowRepository follows;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("RECOMMENDATION_POSTGRES_TEST_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        if (!url.matches("jdbc:postgresql://[^/]+/gamerin_recommendation_test(\\?.*)?")) {
            throw new IllegalArgumentException("전용 gamerin_recommendation_test DB만 사용할 수 있습니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("RECOMMENDATION_POSTGRES_TEST_USERNAME", "postgres"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("RECOMMENDATION_POSTGRES_TEST_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void fofCountsDistinctIntermediariesAndExcludesSelfFollowedAndInactiveUsers() {
        User viewer = saveUser("viewer");
        User a = saveUser("a");
        User b = saveUser("b");
        User suspendedA = saveUser("suspended_a");
        User deletedA = saveUser("deleted_a");
        User popular = saveUser("popular");
        User recent = saveUser("recent");
        User older = saveUser("older");
        User inactiveOnly = saveUser("inactive_only");
        User suspended = saveUser("suspended");
        User deleted = saveUser("deleted");
        for (User intermediary : List.of(a, b, suspendedA, deletedA)) {
            follow(viewer, intermediary);
        }
        for (User candidate : List.of(viewer, b, popular, recent, older, suspended, deleted)) {
            follow(a, candidate);
        }
        follow(b, popular);
        follow(suspendedA, inactiveOnly);
        follow(deletedA, inactiveOnly);
        suspendedA.suspend();
        suspended.suspend();
        em.flush();
        // deleted_at 자체로도 제외해야 한다(상태 값이 아직 ACTIVE인 경우).
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id IN (?, ?)", BASE, deleted.getId(), deletedA.getId());
        activity(recent, 3);
        activity(older, 2);
        activity(popular, 1);
        em.clear();

        var result = follows.findRecommendationCandidates(viewer.getId(), 20);
        assertThat(result).extracting(FollowRepository.RecommendationCandidate::getUserId)
                .containsExactly(popular.getId().toString(), recent.getId().toString(), older.getId().toString());
        assertThat(result).extracting(FollowRepository.RecommendationCandidate::getMutualCount)
                .containsExactly(2L, 1L, 1L);
        assertThat(service.recommend(viewer.getId(), 2)).extracting(UserRecommendationResponse::userId)
                .containsExactly(popular.getId(), recent.getId());
    }

    @Test
    void equalScoresAndActivityUseStableDescendingIdOrder() {
        User viewer = saveUser("viewer");
        User a = saveUser("a");
        User first = saveUser("first");
        User second = saveUser("second");
        follow(viewer, a);
        follow(a, first);
        follow(a, second);
        activity(first, 1);
        activity(second, 1);
        em.clear();

        List<String> expected = List.of(first.getId().toString(), second.getId().toString()).stream()
                .sorted(Comparator.reverseOrder()).toList();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(service.recommend(viewer.getId(), 2)).extracting(r -> r.userId().toString())
                .containsExactlyElementsOf(expected);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void fillsOnlyMissingSlotsWithoutDuplicatesAndWithoutNPlusOne() {
        User viewer = saveUser("viewer");
        User a = saveUser("a");
        User fof = saveUser("fof");
        User fallback = saveUser("fallback");
        follow(viewer, a);
        follow(a, fof);
        activity(fof, 1);
        activity(fallback, 10);
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var result = service.recommend(viewer.getId(), 5);

        assertThat(result).extracting(UserRecommendationResponse::userId).containsExactly(fof.getId(), fallback.getId());
        assertThat(result).extracting(UserRecommendationResponse::mutualCount).containsExactly(1L, 0L);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
    }

    @Test
    void newUserGetsRecentLoginsOrSignupsAndStableFallbackTies() {
        User viewer = saveUser("viewer");
        User oldSignup = saveUser("old_signup");
        User newSignup = saveUser("new_signup");
        User loggedIn = saveUser("logged_in");
        activity(oldSignup, 1);
        activity(newSignup, 2);
        activity(loggedIn, 1);
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?", BASE.plusDays(3), loggedIn.getId());
        em.clear();

        assertThat(service.recommend(viewer.getId(), 2)).extracting(UserRecommendationResponse::userId)
                .containsExactly(loggedIn.getId(), newSignup.getId());
        activity(newSignup, 1);
        List<String> tied = List.of(oldSignup.getId().toString(), newSignup.getId().toString()).stream()
                .sorted(Comparator.reverseOrder()).toList();
        assertThat(service.recommend(viewer.getId(), 3)).extracting(r -> r.userId().toString())
                .containsExactly(loggedIn.getId().toString(), tied.get(0), tied.get(1));
    }

    @Test
    void exhaustedPoolReturnsEmptyAndFallbackAlsoExcludesInactiveAndFollowed() {
        User viewer = saveUser("viewer");
        User followed = saveUser("followed");
        User suspended = saveUser("suspended");
        User deleted = saveUser("deleted");
        follow(viewer, followed);
        suspended.suspend();
        deleted.withdraw();
        em.flush();
        em.clear();

        assertThat(service.recommend(viewer.getId(), 20)).isEmpty();
    }

    @Test
    void httpContractIncludesCachedGameSummaryAndActiveMembershipWithoutPrivateIdentifiers() throws Exception {
        User viewer = saveUser("viewer");
        User candidate = saveUser("candidate");
        candidate.getProfile().updateProfileImageUrl("/uploads/candidate.jpg");
        candidate.getProfile().updateGameStats(Map.of(
                "RIOT", Map.of("connected", true, "puuid", "private-puuid"),
                "LOL", Map.of("tierLabel", "MASTER", "winRate", 58.5)));
        candidate.getProfile().changeRepresentativeGame(GameType.LOL);
        em.flush();
        jdbc.update("INSERT INTO user_memberships(user_id, expires_at, updated_at) VALUES (?, ?, ?)",
                candidate.getId(), OffsetDateTime.now().plusDays(1), BASE);
        em.clear();

        mvc.perform(get(PATH).with(user(CustomUserPrincipal.from(viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].userId").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.data[0].handle").value("candidate"))
                .andExpect(jsonPath("$.data[0].nickname").value("candidate"))
                .andExpect(jsonPath("$.data[0].profileImageUrl").value("/uploads/candidate.jpg"))
                .andExpect(jsonPath("$.data[0].membershipBadge").value(true))
                .andExpect(jsonPath("$.data[0].mutualCount").value(0))
                .andExpect(jsonPath("$.data[0].representativeTier.game").value("LOL"))
                .andExpect(jsonPath("$.data[0].representativeTier.tierLabel").value("MASTER"))
                .andExpect(jsonPath("$.data[0].winRate").value(58.5))
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.data[0].gameStats").doesNotExist());
        jdbc.update("UPDATE user_memberships SET expires_at = ? WHERE user_id = ?", BASE, candidate.getId());
        assertThat(service.recommend(viewer.getId(), 1).getFirst().membershipBadge()).isFalse();
    }

    @Test
    void defaultLimitIsFiveAndMaximumLimitIsAccepted() throws Exception {
        User viewer = saveUser("viewer");
        for (int i = 0; i < 6; i++) {
            saveUser("candidate" + i);
        }
        em.flush();
        em.clear();
        mvc.perform(get(PATH).with(user(CustomUserPrincipal.from(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(5));
        mvc.perform(get(PATH).param("limit", "20").with(user(CustomUserPrincipal.from(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(6));
    }

    @Test
    void twentyProfilesStillUseFourQueriesAndMissingStatsStayNull() {
        User viewer = saveUser("viewer");
        for (int i = 0; i < 20; i++) {
            saveUser("candidate" + i);
        }
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var result = service.recommend(viewer.getId(), 20);

        assertThat(result).hasSize(20).allSatisfy(candidate -> {
            assertThat(candidate.representativeTier()).isNull();
            assertThat(candidate.winRate()).isNull();
            assertThat(candidate.membershipBadge()).isFalse();
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    }

    @Test
    void openApiDocumentsBearerAuthenticationLimitAndArrayResponse() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.parameters[0].schema.type").value("integer"))
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.parameters[0].schema.minimum").value(1))
                .andExpect(jsonPath("$.paths['" + PATH + "'].get.parameters[0].schema.maximum").value(20))
                .andExpect(jsonPath("$.components.schemas.ApiResponseListUserRecommendationResponse.properties.data.type").value("array"))
                .andExpect(jsonPath("$.components.schemas.UserRecommendationResponse.properties.mutualCount").exists())
                .andExpect(jsonPath("$.components.schemas.UserRecommendationResponse.properties.winRate.type")
                        .value(org.hamcrest.Matchers.hasItem("null")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "21", "2147483647", "2147483648", "abc"})
    void invalidLimitsReturnJson400(String limit) throws Exception {
        User viewer = saveUser("viewer");
        mvc.perform(get(PATH).param("limit", limit).with(user(CustomUserPrincipal.from(viewer))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void anonymousAndSuspendedViewersCannotReadRecommendations() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        User viewer = saveUser("viewer");
        viewer.suspend();
        mvc.perform(get(PATH).with(user(CustomUserPrincipal.from(viewer))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
    }

    private User saveUser(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "hash");
        user.setProfile(UserProfile.createDefault(user));
        em.persist(user);
        em.flush();
        return user;
    }

    private void follow(User follower, User followee) {
        em.persist(Follow.create(follower, followee));
        em.flush();
    }

    private void activity(User user, int day) {
        jdbc.update("UPDATE users SET created_at = ?, last_login_at = NULL WHERE id = ?", BASE.plusDays(day), user.getId());
    }
}
