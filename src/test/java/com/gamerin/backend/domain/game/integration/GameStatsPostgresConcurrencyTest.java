package com.gamerin.backend.domain.game.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.game.service.GameStatsPersistenceService;
import com.gamerin.backend.domain.pubg.client.PubgApiClient;
import com.gamerin.backend.domain.pubg.dto.request.PubgConnectRequest;
import com.gamerin.backend.domain.pubg.model.RankedStats;
import com.gamerin.backend.domain.pubg.service.PubgService;
import com.gamerin.backend.domain.r6.client.R6StatsClient;
import com.gamerin.backend.domain.r6.controller.R6Controller;
import com.gamerin.backend.domain.r6.dto.request.R6ConnectRequest;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;
import com.gamerin.backend.domain.r6.service.R6Service;
import com.gamerin.backend.domain.riot.client.RiotApiClient;
import com.gamerin.backend.domain.riot.controller.RiotController;
import com.gamerin.backend.domain.riot.dto.external.LeagueEntryResponse;
import com.gamerin.backend.domain.riot.dto.external.RiotAccountResponse;
import com.gamerin.backend.domain.riot.dto.request.RiotConnectRequest;
import com.gamerin.backend.domain.riot.service.RiotService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import com.gamerin.backend.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "GAME_STATS_POSTGRES_TEST_URL", matches = ".+")
@SpringJUnitConfig(GameStatsPostgresConcurrencyTest.PersistenceTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameStatsPostgresConcurrencyTest {

    private static final R6SummaryStats R6_SUMMARY = new R6SummaryStats("Gold", 1.25, 60.0, 10, GameStatsMode.RANKED);
    private static final RankedStats PUBG_SUMMARY = new RankedStats(1.25, 10, 6, "Gold", "I");
    private static final List<LeagueEntryResponse> LOL_SUMMARY = List.of(
            new LeagueEntryResponse("league", "RANKED_SOLO_5x5", "GOLD", "I", "puuid", 10, 6, 4)
    );
    private static String schema;

    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RiotService riotService;
    @Autowired private R6Service r6Service;
    @Autowired private PubgService pubgService;
    @MockitoSpyBean private GameStatsPersistenceService gameStatsPersistenceService;
    @Autowired private ApplicationContext applicationContext;
    @MockitoBean private RiotApiClient riotApiClient;
    @MockitoBean private R6StatsClient r6StatsClient;
    @MockitoBean private PubgApiClient pubgApiClient;

    // Load only persistence and game services, without local config files or application schedulers.
    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class, FlywayAutoConfiguration.class})
    @EntityScan("com.gamerin.backend.domain")
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import({GameStatsPersistenceService.class, RiotService.class, R6Service.class, PubgService.class})
    static class PersistenceTestConfiguration { }

    @Test
    void testContextUsesOnlyDedicatedSchemaWithoutApplicationScheduling() {
        assertThat(jdbcTemplate.queryForObject("select current_schema()", String.class)).isEqualTo(schema);
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(applicationContext.getEnvironment().getActiveProfiles()).doesNotContain("local", "prod");
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) throws SQLException {
        schema = "game_stats_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        executeSchemaStatement("create schema " + schema);
        registry.add("spring.datasource.url", () -> environment("URL"));
        registry.add("spring.datasource.username", () -> environment("USERNAME"));
        registry.add("spring.datasource.password", () -> environment("PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", () -> schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @AfterAll
    static void removeTemporarySchema() throws SQLException {
        if (schema != null) {
            executeSchemaStatement("drop schema " + schema + " cascade");
        }
    }

    @BeforeEach
    void configureExternalResponses() {
        when(riotApiClient.findAccount(anyString(), anyString())).thenAnswer(call ->
                new RiotAccountResponse(call.getArgument(0), call.getArgument(0), call.getArgument(1)));
        when(riotApiClient.findLeagueEntriesByPuuid(anyString())).thenReturn(LOL_SUMMARY);
        when(riotApiClient.findRecentMatchIds(anyString(), anyInt())).thenReturn(List.of());
        when(r6StatsClient.findProfile(anyString())).thenAnswer(call ->
                new R6Profile(call.getArgument(0), call.getArgument(0), R6_SUMMARY));
        when(r6StatsClient.getSummary(any())).thenReturn(R6_SUMMARY);
        when(pubgApiClient.findAccountId(anyString())).thenAnswer(call -> call.getArgument(0));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season");
        when(pubgApiClient.getRankedStats(anyString(), anyString(), anyString())).thenReturn(PUBG_SUMMARY);
    }

    @Test
    void lolRefreshPreservesR6ConnectionCommittedDuringExternalCall() throws Exception {
        Fixture fixture = fixture();
        connect(Game.RIOT, fixture, "original");
        duringRefresh(Game.RIOT, fixture, () -> connect(Game.R6, fixture, "new"));

        UserProfile profile = profile(fixture);
        assertThat(profile.hasConnectedR6()).isTrue();
        assertThat(profile.getR6AccountId()).isEqualTo(account(fixture, "new"));
        assertThat(profile.getGameStats()).containsKeys("RIOT", "LOL", "R6");
        assertThat(section(profile, "LOL")).containsEntry("games", 10);
        assertThat(jdbcTemplate.queryForObject(
                "select pg_typeof(game_stats)::text from user_profiles where user_id = ?",
                String.class, fixture.userId())).isEqualTo("jsonb");
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void otherGamesRefreshesPreserveBothCommittedResults(Game game) throws Exception {
        Fixture fixture = fixture();
        Game other = game == Game.RIOT ? Game.R6 : Game.RIOT;
        connect(game, fixture, "first");
        connect(other, fixture, "second");
        duringRefresh(game, fixture, () -> refresh(other, fixture));

        UserProfile profile = profile(fixture);
        assertThat(section(profile, game.statsKey())).containsEntry(game.matchesKey(), 10);
        assertThat(section(profile, other.statsKey())).containsEntry(other.matchesKey(), 10);
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void connectionPreservesAnotherGamesRefreshCommittedDuringAccountLookup(Game game) throws Exception {
        Fixture fixture = fixture();
        Game other = game == Game.RIOT ? Game.PUBG : Game.RIOT;
        connect(other, fixture, "existing");
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseConnect(game, () -> {
            loaded.countDown();
            await(resume);
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> connecting = executor.submit(() -> connect(game, fixture, "new"));
            try {
                await(loaded);
                refresh(other, fixture);
            } finally {
                resume.countDown();
            }
            connecting.get(15, TimeUnit.SECONDS);
        }
        assertThat(section(profile(fixture), game.name())).containsEntry("connected", true);
        assertThat(section(profile(fixture), other.statsKey())).containsEntry(other.matchesKey(), 10);
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void disconnectDuringRefreshRemainsDisconnected(Game game) throws Exception {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        duringRefresh(game, fixture, () -> disconnect(game, fixture));

        assertThat(profile(fixture).getGameStats()).doesNotContainKeys(game.name(), game.statsKey());
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void replacementDuringRefreshPreservesNewAccountAndItsCache(Game game) throws Exception {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        refresh(game, fixture);
        assertThat(section(profile(fixture), game.statsKey())).containsEntry(game.matchesKey(), 10);
        AtomicBoolean replacementCommitted = new AtomicBoolean();
        duringRefresh(game, fixture, () -> {
            connect(game, fixture, "replacement");
            replacementCommitted.set(true);
        });

        assertThat(replacementCommitted).isTrue();
        UserProfile profile = profile(fixture);
        assertThat(section(profile, game.name()))
                .containsEntry(game == Game.RIOT ? "puuid" : "accountId", account(fixture, "replacement"));
        if (game == Game.RIOT) {
            assertThat(profile.getGameStats()).doesNotContainKey("LOL");
        } else if (game == Game.PUBG) {
            assertThat(section(profile, "PUBG")).doesNotContainKey("matches");
        } else {
            assertThat(section(profile, "R6")).containsEntry("tierLabel", "Gold");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"RIOT", "PUBG"})
    void accountReplacementClearsPreviouslyCommittedSummaryAndPreservesOtherGames(Game game) {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        refresh(game, fixture);
        connect(Game.R6, fixture, "unrelated");
        UserProfile before = profile(fixture);
        assertThat(section(before, game.statsKey())).containsEntry(game.matchesKey(), 10);

        connect(game, fixture, "replacement");

        UserProfile after = profile(fixture);
        assertThat(section(after, game.name()))
                .containsEntry("connected", true)
                .containsEntry(game == Game.RIOT ? "puuid" : "accountId", account(fixture, "replacement"));
        assertSummaryAbsent(after, game);
        assertThat(section(after, "R6")).isEqualTo(section(before, "R6"));
        assertThat(after.getGameConnectionVersion(game.name()))
                .isEqualTo(before.getGameConnectionVersion(game.name()) + 1);

        String replacementAccount = account(fixture, "replacement");
        if (game == Game.RIOT) {
            when(riotApiClient.findLeagueEntriesByPuuid(replacementAccount)).thenReturn(List.of(
                    new LeagueEntryResponse("replacement-league", "RANKED_SOLO_5x5", "PLATINUM", "II",
                            replacementAccount, 50, 15, 5)));
        } else {
            when(pubgApiClient.getRankedStats(replacementAccount, "season", "squad"))
                    .thenReturn(new RankedStats(2.5, 20, 15, "Platinum", "II"));
        }
        refresh(game, fixture);

        UserProfile refreshed = profile(fixture);
        assertThat(section(refreshed, game.statsKey()))
                .containsEntry(game.matchesKey(), 20)
                .containsEntry("winRate", 75)
                .containsEntry("tierLabel", game == Game.RIOT ? "PLATINUM II" : "Platinum II");
        assertThat(section(refreshed, game.name()))
                .containsEntry(game == Game.RIOT ? "puuid" : "accountId", replacementAccount);
        assertThat(section(refreshed, "R6")).isEqualTo(section(before, "R6"));
        assertThat(refreshed.getGameConnectionVersion(game.name()))
                .isEqualTo(after.getGameConnectionVersion(game.name()));
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"RIOT", "PUBG"})
    void failedReplacementSummaryLookupDoesNotRestorePreviousAccountsCache(Game game) {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        refresh(game, fixture);
        assertThat(section(profile(fixture), game.statsKey())).containsEntry(game.matchesKey(), 10);
        connect(game, fixture, "replacement");
        UserProfile beforeFailure = profile(fixture);
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream unavailable");
        if (game == Game.RIOT) {
            when(riotApiClient.findLeagueEntriesByPuuid(account(fixture, "replacement"))).thenThrow(failure);
        } else {
            when(pubgApiClient.getRankedStats(account(fixture, "replacement"), "season", "squad")).thenThrow(failure);
        }

        assertThatThrownBy(() -> refresh(game, fixture)).isSameAs(failure);

        UserProfile afterFailure = profile(fixture);
        assertSummaryAbsent(afterFailure, game);
        assertThat(afterFailure.getGameStats()).isEqualTo(beforeFailure.getGameStats());
        assertThat(afterFailure.getGameConnectionVersion(game.name()))
                .isEqualTo(beforeFailure.getGameConnectionVersion(game.name()));
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"RIOT", "PUBG"})
    void reconnectingSameAccountPreservesPreviouslyCommittedSummary(Game game) {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        refresh(game, fixture);
        UserProfile before = profile(fixture);
        assertThat(section(before, game.statsKey())).containsEntry(game.matchesKey(), 10);

        connect(game, fixture, "original");

        UserProfile after = profile(fixture);
        assertThat(after.getGameStats()).isEqualTo(before.getGameStats());
        assertThat(after.getGameConnectionVersion(game.name()))
                .isEqualTo(before.getGameConnectionVersion(game.name()) + 1);
    }

    private void assertSummaryAbsent(UserProfile profile, Game game) {
        if (game == Game.RIOT) {
            assertThat(profile.getGameStats()).doesNotContainKey("LOL");
        } else {
            assertThat(section(profile, "PUBG"))
                    .doesNotContainKeys("tierLabel", "kd", "winRate", "matches", "statsMode");
        }
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void disconnectAndReconnectSameAccountRejectsPreviousRefresh(Game game) throws Exception {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        duringRefresh(game, fixture, () -> {
            disconnect(game, fixture);
            connect(game, fixture, "original");
        });

        UserProfile profile = profile(fixture);
        assertThat(section(profile, game.name())).containsEntry("connected", true);
        if (game == Game.RIOT) {
            assertThat(profile.getGameStats()).doesNotContainKey("LOL");
        } else if (game == Game.PUBG) {
            assertThat(section(profile, "PUBG")).doesNotContainKey("matches");
        } else {
            assertThat(section(profile, "R6")).containsEntry("tierLabel", "Gold");
        }
    }

    @Test
    void staleProfileEditCannotOverwriteCommittedGameStats() throws Exception {
        Fixture fixture = fixture();
        connect(Game.RIOT, fixture, "original");
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> editing = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                UserProfile profile = userRepository.findById(fixture.userId()).orElseThrow().getProfile();
                profile.getGameStats().size();
                loaded.countDown();
                await(resume);
                profile.updateBio("new bio");
            }));
            try {
                await(loaded);
                connect(Game.R6, fixture, "new");
            } finally {
                resume.countDown();
            }
            editing.get(15, TimeUnit.SECONDS);
        }
        assertThat(profile(fixture).getBio()).isEqualTo("new bio");
        assertThat(profile(fixture).hasConnectedR6()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void externalRefreshDoesNotHoldDatabaseTransaction(Game game) throws Exception {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        AtomicBoolean transactionActive = new AtomicBoolean();
        pauseExternal(game, () -> transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive()));
        refresh(game, fixture);
        assertThat(transactionActive).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void externalAccountLookupSuspendsAnyCallingTransaction(Game game) {
        Fixture fixture = fixture();
        AtomicBoolean transactionActive = new AtomicBoolean();
        pauseConnect(game, () -> transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive()));
        transactionTemplate.executeWithoutResult(status -> connect(game, fixture, "new"));
        assertThat(transactionActive).isFalse();
    }

    @Test
    void failedConnectionWriteRollsBackJsonAndConnectionVersionTogether() {
        Fixture fixture = fixture();
        connect(Game.RIOT, fixture, "original");
        UserProfile before = profile(fixture);
        assertThatThrownBy(() -> gameStatsPersistenceService.updateConnection(fixture.userId(), current -> {
            current.disconnectRiot();
            throw new IllegalStateException("failed before commit");
        })).isInstanceOf(IllegalStateException.class);

        UserProfile after = profile(fixture);
        assertThat(after.getGameStats()).isEqualTo(before.getGameStats());
        assertThat(after.getGameConnectionVersion("RIOT")).isEqualTo(before.getGameConnectionVersion("RIOT"));
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"R6", "RIOT"})
    void simultaneousDuplicateConnectionsReturnOneSuccessAndOneConflict(Game game) throws Exception {
        duplicateConnections(game, false);
    }

    @Test
    void simultaneousR6ConnectionsIgnoreAccountIdCase() throws Exception {
        duplicateConnections(Game.R6, true);
    }

    private void duplicateConnections(Game game, boolean differentCase) throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        // Both users already have data: the losing replacement must roll back all JSON and its version.
        for (Fixture fixture : List.of(first, second)) {
            connect(game, fixture, "original");
            refresh(game, fixture);
            connect(Game.PUBG, fixture, "unrelated");
        }
        UserProfile firstBefore = profile(first);
        UserProfile secondBefore = profile(second);
        String shared = account(first, "SharedAccount");
        String secondAccount = differentCase ? shared.toUpperCase(Locale.ROOT) : shared;
        MockMvc mvc = connectionMvc();
        pauseBothConnectionWrites();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MockHttpServletResponse> one = executor.submit(() -> connectHttp(mvc, game, first, shared));
            Future<MockHttpServletResponse> two = executor.submit(() -> connectHttp(mvc, game, second, secondAccount));
            MockHttpServletResponse firstResponse = one.get(15, TimeUnit.SECONDS);
            MockHttpServletResponse secondResponse = two.get(15, TimeUnit.SECONDS);
            assertThat(List.of(firstResponse.getStatus(), secondResponse.getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
            boolean firstWon = firstResponse.getStatus() == 200;
            MockHttpServletResponse rejected = firstWon ? secondResponse : firstResponse;
            var body = new ObjectMapper().readTree(rejected.getContentAsByteArray());
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("message").asText()).isEqualTo(game == Game.R6
                    ? "이미 다른 유저가 사용 중인 R6 계정입니다." : "이미 다른 유저가 연동한 Riot 계정입니다.");
            UserProfile loser = profile(firstWon ? second : first);
            UserProfile before = firstWon ? secondBefore : firstBefore;
            assertThat(loser.getGameStats()).isEqualTo(before.getGameStats());
            assertThat(loser.getGameConnectionVersion(game.name())).isEqualTo(before.getGameConnectionVersion(game.name()));
            UserProfile winner = profile(firstWon ? first : second);
            assertThat(section(winner, "PUBG")).isEqualTo(section(firstWon ? firstBefore : secondBefore, "PUBG"));
        }
        String identifier = game == Game.R6 ? "LOWER(game_stats -> 'R6' ->> 'accountId')"
                : "(game_stats -> 'RIOT' ->> 'puuid')";
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_profiles where " + identifier + " = ?",
                Integer.class, game == Game.R6 ? shared.toLowerCase(Locale.ROOT) : shared)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"R6", "RIOT"})
    void disconnectReleasesAccountForAnotherUserAndSameUserCanReconnect(Game game) throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        String account = account(first, "reusable");
        MockMvc mvc = connectionMvc();
        assertThat(connectHttp(mvc, game, first, account).getStatus()).isEqualTo(200);
        assertThat(connectHttp(mvc, game, first, account).getStatus()).isEqualTo(200);
        assertThat(connectHttp(mvc, game, second, account).getStatus()).isEqualTo(409);
        disconnect(game, first);
        assertThat(connectHttp(mvc, game, second, account).getStatus()).isEqualTo(200);
        assertThat(profile(first).getGameStats()).doesNotContainKey(game.name());
        assertThat(section(profile(second), game.name())).containsEntry("connected", true);
    }

    @ParameterizedTest
    @EnumSource(value = Game.class, names = {"R6", "RIOT"})
    void simultaneousDistinctAccountsBothSucceed(Game game) throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        MockMvc mvc = connectionMvc();
        pauseBothConnectionWrites();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MockHttpServletResponse> one = executor.submit(() -> connectHttp(mvc, game, first, account(first, "one")));
            Future<MockHttpServletResponse> two = executor.submit(() -> connectHttp(mvc, game, second, account(second, "two")));
            assertThat(one.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(two.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void riotIdentifiersRemainCaseSensitiveAndGamesHaveSeparateOwnership() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        String account = account(first, "CaseSensitive");
        MockMvc mvc = connectionMvc();
        assertThat(connectHttp(mvc, Game.RIOT, first, account).getStatus()).isEqualTo(200);
        assertThat(connectHttp(mvc, Game.RIOT, second, account.toUpperCase(Locale.ROOT)).getStatus()).isEqualTo(200);
        assertThat(connectHttp(mvc, Game.R6, second, account).getStatus()).isEqualTo(200);
    }

    private void pauseBothConnectionWrites() {
        CountDownLatch ready = new CountDownLatch(2);
        // The real duplicate SELECT has completed for each request before either profile is changed.
        doAnswer(call -> {
            ready.countDown();
            await(ready);
            return call.callRealMethod();
        }).when(gameStatsPersistenceService).updateConnection(any(), any());
    }

    private MockMvc connectionMvc() {
        // Exercise the real controllers and error contract; authentication filters are covered separately.
        return MockMvcBuilders.standaloneSetup(new R6Controller(r6Service), new RiotController(riotService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private MockHttpServletResponse connectHttp(MockMvc mvc, Game game, Fixture fixture, String account) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(fixture.principal(), null, List.of()));
        try {
            String field = game == Game.R6 ? "playerName" : "riotId";
            String value = game == Game.R6 ? account : account + "#KR1";
            return mvc.perform(post("/api/v1/" + game.name().toLowerCase(Locale.ROOT) + "/connect")
                    .contentType("application/json")
                    .content(new ObjectMapper().writeValueAsBytes(Map.of(field, value))))
                    .andReturn().getResponse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @EnumSource(Game.class)
    void legacyConnectionWithoutVersionCanRefreshAndDisconnect(Game game) {
        Fixture fixture = fixture();
        connect(game, fixture, "original");
        jdbcTemplate.update("update user_profiles set game_connection_versions = '{}' where user_id = ?", fixture.userId());
        assertThat(profile(fixture).getGameConnectionVersion(game.name())).isZero();
        refresh(game, fixture);
        assertThat(section(profile(fixture), game.statsKey())).containsEntry(game.matchesKey(), 10);
        disconnect(game, fixture);
        assertThat(profile(fixture).getGameStats()).doesNotContainKeys(game.name(), game.statsKey());
    }

    private void pauseConnect(Game game, Runnable pause) {
        switch (game) {
            case RIOT -> when(riotApiClient.findAccount(anyString(), anyString())).thenAnswer(call -> {
                pause.run();
                return new RiotAccountResponse(call.getArgument(0), call.getArgument(0), call.getArgument(1));
            });
            case R6 -> when(r6StatsClient.findProfile(anyString())).thenAnswer(call -> {
                pause.run();
                return new R6Profile(call.getArgument(0), call.getArgument(0), R6_SUMMARY);
            });
            case PUBG -> when(pubgApiClient.findAccountId(anyString())).thenAnswer(call -> {
                pause.run();
                return call.getArgument(0);
            });
        }
    }

    private void duringRefresh(Game game, Fixture fixture, Runnable concurrent) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        pauseExternal(game, () -> {
            loaded.countDown();
            await(resume);
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> request = executor.submit(() -> refresh(game, fixture));
            try {
                await(loaded);
                concurrent.run();
            } finally {
                resume.countDown();
            }
            request.get(15, TimeUnit.SECONDS);
        }
    }

    private void pauseExternal(Game game, Runnable pause) {
        switch (game) {
            case RIOT -> when(riotApiClient.findLeagueEntriesByPuuid(anyString())).thenAnswer(call -> {
                pause.run();
                return LOL_SUMMARY;
            });
            case R6 -> when(r6StatsClient.getSummary(any())).thenAnswer(call -> {
                pause.run();
                return new R6SummaryStats("Diamond", 2.5, 80.0, 10, GameStatsMode.RANKED);
            });
            case PUBG -> when(pubgApiClient.getRankedStats(anyString(), anyString(), anyString())).thenAnswer(call -> {
                pause.run();
                return PUBG_SUMMARY;
            });
        }
    }

    private Fixture fixture() {
        return transactionTemplate.execute(status -> {
            String name = "stats_" + UUID.randomUUID().toString().replace("-", "");
            User user = User.createLocal(name + "@example.com", name, name, "encoded-password");
            user.setProfile(UserProfile.createDefault(user));
            userRepository.saveAndFlush(user);
            return new Fixture(user.getId(), CustomUserPrincipal.from(user));
        });
    }

    private UserProfile profile(Fixture fixture) {
        return transactionTemplate.execute(status -> {
            UserProfile result = userRepository.findById(fixture.userId()).orElseThrow().getProfile();
            result.getGameStats().size();
            return result;
        });
    }

    private void connect(Game game, Fixture fixture, String suffix) {
        String account = account(fixture, suffix);
        switch (game) {
            case RIOT -> riotService.connect(fixture.principal(), new RiotConnectRequest(account + "#KR1"));
            case R6 -> r6Service.connect(fixture.principal(), new R6ConnectRequest(account));
            case PUBG -> pubgService.connect(fixture.principal(), new PubgConnectRequest(account));
        }
    }

    private void disconnect(Game game, Fixture fixture) {
        switch (game) {
            case RIOT -> riotService.disconnect(fixture.principal());
            case R6 -> r6Service.disconnect(fixture.principal());
            case PUBG -> pubgService.disconnect(fixture.principal());
        }
    }

    private void refresh(Game game, Fixture fixture) {
        switch (game) {
            case RIOT -> riotService.getLolSummary(fixture.principal());
            case R6 -> r6Service.refreshMySummary(fixture.principal());
            case PUBG -> pubgService.getMySummary(fixture.principal());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(UserProfile profile, String key) {
        return (Map<String, Object>) profile.getGameStats().get(key);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("controlled request ordering").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating requests", e);
        }
    }

    private static String account(Fixture fixture, String suffix) {
        return fixture.userId().toString().replace("-", "") + suffix;
    }

    private static String environment(String suffix) {
        String value = System.getenv("GAME_STATS_POSTGRES_TEST_" + suffix);
        if (value == null) {
            throw new IllegalStateException("GAME_STATS_POSTGRES_TEST_" + suffix + " must be set");
        }
        return value;
    }

    private static void executeSchemaStatement(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(environment("URL"), environment("USERNAME"), environment("PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Fixture(UUID userId, CustomUserPrincipal principal) { }

    private enum Game {
        RIOT, R6, PUBG;
        String statsKey() { return this == RIOT ? "LOL" : name(); }
        String matchesKey() { return this == RIOT ? "games" : "matches"; }
    }
}
