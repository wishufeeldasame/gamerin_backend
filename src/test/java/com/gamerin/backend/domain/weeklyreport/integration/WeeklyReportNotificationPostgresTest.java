package com.gamerin.backend.domain.weeklyreport.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.gamerin.backend.domain.membership.entity.UserMembership;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.notification.entity.NotificationType;
import com.gamerin.backend.domain.notification.repository.NotificationRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.valorant.model.ValorantSummaryStats;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.repository.WeeklyGameReportRepository;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportNotificationService;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportPersistenceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({WeeklyReportPersistenceService.class, WeeklyReportNotificationService.class, MembershipService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "WEEKLY_REPORT_POSTGRES_TEST_URL", matches = ".+")
class WeeklyReportNotificationPostgresTest {
    private static final String SCHEMA = "weekly_notify_" + UUID.randomUUID().toString().replace("-", "");
    private static final WeeklyReportPeriod PERIOD = new WeeklyReportPeriod(LocalDate.parse("2026-11-02"));
    @Autowired private WeeklyReportPersistenceService persistence;
    @Autowired private WeeklyGameReportRepository reports;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private NotificationRepository notifications;
    private String puuid;
    private UUID recipientId;

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(System.getenv("WEEKLY_REPORT_POSTGRES_TEST_URL"),
                System.getenv().getOrDefault("WEEKLY_REPORT_POSTGRES_TEST_USERNAME", "postgres"),
                System.getenv().getOrDefault("WEEKLY_REPORT_POSTGRES_TEST_PASSWORD", ""));
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add("spring.datasource.url", () -> System.getenv("WEEKLY_REPORT_POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("WEEKLY_REPORT_POSTGRES_TEST_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("WEEKLY_REPORT_POSTGRES_TEST_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void fixture() {
        reset(notifications);
        puuid = UUID.randomUUID().toString();
        recipientId = transactions.execute(status -> {
            String handle = "weekly_" + puuid.substring(0, 8);
            User user = users.saveAndFlush(User.createLocal(handle + "@test.invalid", handle, "리포트 회원", "test"));
            UserProfile profile = UserProfile.createDefault(user);
            profile.connectValorant("test#KR1", puuid, "KR", "PC",
                    new ValorantSummaryStats(null, null, null, null, null, null, null, null), OffsetDateTime.now());
            entityManager.persist(profile);
            entityManager.persist(UserMembership.create(user, OffsetDateTime.now().plusDays(7)));
            return user.getId();
        });
    }

    @Test
    void readyReportCreatesOneVisibleNotificationAndRegenerationPreservesReadState() {
        var first = persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        assertThat(notifications.countValidUnreadByRecipientId(recipientId)).isEqualTo(1);
        var ids = notifications.findValidPageIds(recipientId, 10);
        assertThat(ids).hasSize(1);
        var notification = transactions.execute(status -> notifications.findAllWithDetailsByIdIn(
                List.of(UUID.fromString(ids.getFirst()))).getFirst());
        assertThat(notification.getType()).isEqualTo(NotificationType.WEEKLY_REPORT_READY);
        assertThat(notification.getActor()).isNull();
        jdbc.update("UPDATE notifications SET read_at = now() WHERE recipient_id = ?", recipientId);

        var again = persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(notifications.findValidPageIds(recipientId, 10)).hasSize(1);
        assertThat(notifications.countValidUnreadByRecipientId(recipientId)).isZero();
        persistence.save(puuid, "VALORANT", new WeeklyReportPeriod(PERIOD.weekStart().plusWeeks(1)),
                WeeklyReportStatus.READY, null, null, null);
        assertThat(notifications.countValidUnreadByRecipientId(recipientId)).isEqualTo(1);
    }

    @Test
    void noDataAndFailureDoNotNotifyButLaterReadyDoes() {
        persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.NO_DATA, null, null, null);
        persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.FAILED, null, null, null);
        assertThat(notifications.findValidPageIds(recipientId, 10)).isEmpty();
        persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        assertThat(notifications.countValidUnreadByRecipientId(recipientId)).isEqualTo(1);
    }

    @Test
    void expiredMembershipAndDisconnectedAccountDoNotReceiveNotifications() {
        jdbc.update("UPDATE user_memberships SET expires_at = now() - interval '1 day' WHERE user_id = ?", recipientId);
        persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        assertThat(notifications.findValidPageIds(recipientId, 10)).isEmpty();
        jdbc.update("UPDATE user_memberships SET expires_at = now() + interval '1 day' WHERE user_id = ?", recipientId);
        jdbc.update("UPDATE user_profiles SET game_stats = '{}'::jsonb WHERE user_id = ?", recipientId);
        persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        assertThat(notifications.findValidPageIds(recipientId, 10)).isEmpty();
    }

    @Test
    void notificationFailureRollsBackReportSoTheBatchCanRetry() {
        doThrow(new IllegalStateException("notification write failed")).when(notifications)
                .insertWeeklyReportReady(any(), any(), any(), any());
        assertThatThrownBy(() -> persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reports.findByPuuidAndGameAndWeekStart(puuid, "VALORANT", PERIOD.weekStart())).isEmpty();
        assertThat(notifications.findValidPageIds(recipientId, 10)).isEmpty();
    }

    @Test
    void concurrentNotificationInsertsAreIdempotent() throws Exception {
        var report = persistence.save(puuid, "VALORANT", PERIOD, WeeklyReportStatus.READY, null, null, null);
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", recipientId);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> transactions.execute(status -> notifications.insertWeeklyReportReady(
                    UUID.randomUUID(), recipientId, report.getId(), OffsetDateTime.now())));
        }
        try (var executor = Executors.newFixedThreadPool(6)) {
            int inserted = 0;
            for (var result : executor.invokeAll(calls, 10, TimeUnit.SECONDS)) inserted += result.get();
            assertThat(inserted).isEqualTo(1);
        }
        assertThat(notifications.countValidUnreadByRecipientId(recipientId)).isEqualTo(1);
    }
}
