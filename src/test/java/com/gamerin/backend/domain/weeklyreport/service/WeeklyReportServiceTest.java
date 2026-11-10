package com.gamerin.backend.domain.weeklyreport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.valorant.client.HenrikValorantClient;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
import com.gamerin.backend.domain.valorant.model.ValorantMatchDetail;
import com.gamerin.backend.domain.valorant.model.ValorantMatchReference;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.gamerin.backend.domain.weeklyreport.client.WeeklyReportAiClient;
import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyGameReport;
import com.gamerin.backend.domain.weeklyreport.entity.WeeklyReportStatus;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportCollectionIssue;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.repository.ValorantMatchRecordRepository;
import com.gamerin.backend.domain.weeklyreport.repository.WeeklyGameReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WeeklyReportServiceTest {

    // KST 2026-11-10(화) → 대상 주 11-02 ~ 11-08, 전주 10-26 ~ 11-01
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate WEEK_START = LocalDate.parse("2026-11-02");
    private static final WeeklyReportPeriod PERIOD = new WeeklyReportPeriod(WEEK_START);
    private static final String PUUID = "puuid";

    private final HenrikValorantClient henrik = mock(HenrikValorantClient.class);
    private final WeeklyReportAiClient ai = mock(WeeklyReportAiClient.class);
    private final ValorantMatchRecordRepository matchRecords = mock(ValorantMatchRecordRepository.class);
    private final WeeklyGameReportRepository reports = mock(WeeklyGameReportRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final WeeklyReportService service = new WeeklyReportService(henrik, ai, matchRecords, reports, users,
            memberships, Duration.ZERO, CLOCK);

    @BeforeEach
    void setUp() {
        when(reports.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reports.findByPuuidAndGameAndWeekStart(any(), any(), any())).thenReturn(Optional.empty());
        when(matchRecords.findFirstByPuuidOrderByStartedAtDesc(PUUID)).thenReturn(Optional.empty());
    }

    @Test
    void nonMemberIsForbiddenBeforeAnyLookup() {
        UUID userId = UUID.randomUUID();
        when(memberships.isActive(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.getMyReport(userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(users, reports, henrik, ai);
    }

    @Test
    void memberWithoutReportGetsEmptyGamesWithoutLazyGeneration() {
        UUID userId = UUID.randomUUID();
        when(memberships.isActive(userId)).thenReturn(true);
        User user = connectedUser(userId);
        when(users.findWithProfileById(userId)).thenReturn(Optional.of(user));

        WeeklyGameReportResponse response = service.getMyReport(userId);

        assertThat(response.weekStart()).isEqualTo(WEEK_START);
        assertThat(response.weekEnd()).isEqualTo(LocalDate.parse("2026-11-08"));
        assertThat(response.previousWeekStart()).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(response.previousWeekEnd()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(response.totalMatches()).isZero();
        assertThat(response.games()).isEmpty();
        verify(reports).findByPuuidAndGameAndWeekStart(PUUID, "VALORANT", WEEK_START);
        verifyNoInteractions(henrik, ai);
    }

    @Test
    void generateStoresOnlyNewMatchesAndUsesAiAnalysis() {
        List<ValorantMatchDetail> fetched = List.of(detail("known", "2026-11-03T00:00:00Z"),
                detail("new", "2026-11-04T00:00:00Z"));
        when(henrik.getCompetitiveMatchesSince(ValorantRegion.KR, PUUID, PERIOD.previousStartInstant().toInstant()))
                .thenReturn(fetched);
        when(matchRecords.findExistingMatchIds(eq(PUUID), anyCollection())).thenReturn(List.of("known"));
        stubStoredMatches(fetched);
        WeeklyAnalysis analysis = new WeeklyAnalysis("제목", "요약", "점검");
        when(ai.analyze(any())).thenReturn(Optional.of(analysis));
        when(ai.model()).thenReturn("model-x");

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValorantMatchRecord>> saved = ArgumentCaptor.forClass(List.class);
        verify(matchRecords).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ValorantMatchRecord::getMatchId).containsExactly("new");
        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.READY);
        assertThat(report.getAnalysis()).isEqualTo(analysis);
        assertThat(report.getAiModel()).isEqualTo("model-x");
        assertThat(report.getStats().current().matches()).isEqualTo(2);
        assertThat(report.getStats().previous()).isNull();
    }

    @Test
    void collectsOnlyAfterLatestStoredMatch() {
        ValorantMatchRecord latest = ValorantMatchRecord.of(PUUID, detail("latest", "2026-11-05T00:00:00Z"));
        when(matchRecords.findFirstByPuuidOrderByStartedAtDesc(PUUID)).thenReturn(Optional.of(latest));
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(List.of());
        stubStoredMatches(List.of());

        service.generate(PUUID, ValorantRegion.KR, PERIOD);

        verify(henrik).getCompetitiveMatchesSince(ValorantRegion.KR, PUUID, Instant.parse("2026-11-05T00:00:00Z"));
        verify(matchRecords, never()).saveAll(any());
    }

    @Test
    void aiFailureFallsBackToRuleBasedTextWithoutModel() {
        List<ValorantMatchDetail> fetched = List.of(detail("m1", "2026-11-03T00:00:00Z"));
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(fetched);
        when(matchRecords.findExistingMatchIds(eq(PUUID), anyCollection())).thenReturn(List.of());
        stubStoredMatches(fetched);
        when(ai.analyze(any())).thenReturn(Optional.empty());
        when(ai.model()).thenReturn("model-x");

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.READY);
        assertThat(report.getAiModel()).isNull();
        assertThat(report.getAnalysis().headline()).isEqualTo("경쟁전 1경기, 승률 100%를 기록한 한 주");
        assertThat(report.getAnalysis().summary())
                .contains("K/D 2", "HS% 26.7%", "골드 1", "전주에 확인된 경쟁전 기록이 없어", "단정하기는 어렵습니다");
        assertThat(report.getAnalysis().focus()).isNotBlank();
    }

    @Test
    void noMatchesInTargetWeekIsNoDataWithoutAiCall() {
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(List.of());
        stubStoredMatches(List.of(detail("prev", "2026-10-28T00:00:00Z")));

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.NO_DATA);
        assertThat(report.getAnalysis()).isNull();
        verifyNoInteractions(ai);
    }

    @Test
    void adminGenerationResolvesRiotIdAndReturnsFrontendShape() {
        when(henrik.findAccount("이름", "KR1")).thenReturn(new ValorantAccount(PUUID, "이름", "KR1"));
        List<ValorantMatchDetail> fetched = List.of(detail("prev", "2026-10-28T00:00:00Z"),
                detail("cur", "2026-11-03T00:00:00Z"));
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(fetched);
        when(matchRecords.findExistingMatchIds(eq(PUUID), anyCollection())).thenReturn(List.of());
        stubStoredMatches(fetched);
        when(ai.analyze(any())).thenReturn(Optional.empty());

        WeeklyGameReportResponse response = service.generateForRiotId(" 이름#KR1 ", ValorantRegion.KR);

        assertThat(response.totalMatches()).isEqualTo(1);
        WeeklyGameReportResponse.GameStat game = response.games().get(0);
        assertThat(game.game()).isEqualTo("VALORANT");
        assertThat(game.gameLabel()).isEqualTo("발로란트");
        assertThat(game.tierLabel()).isEqualTo("골드 1");
        assertThat(game.previousWeek().matches()).isEqualTo(1);
        assertThat(game.analysis()).isNotNull();
    }

    @Test
    void incompleteCollectionDoesNotSaveMatchesOrGenerateReport() {
        when(henrik.getCompetitiveMatchesSince(any(), eq(PUUID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "incomplete history"));

        assertThatThrownBy(() -> service.generate(PUUID, ValorantRegion.KR, PERIOD))
                .isInstanceOf(ResponseStatusException.class);

        verify(matchRecords, never()).saveAll(any());
        verify(reports, never()).save(any());
        verifyNoInteractions(ai);
    }

    @Test
    void batchTargetsActiveConnectedMembersSkipsDoneAndRecordsFailure() {
        UUID done = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(users.findConnectedValorantUserIds()).thenReturn(List.of(done, failing));
        when(memberships.findActiveUserIds(List.of(done, failing))).thenReturn(new java.util.LinkedHashSet<>(
                List.of(done, failing)));
        User doneUser = connectedUser(done, "done-puuid");
        User failingUser = connectedUser(failing, PUUID);
        when(users.findWithProfileById(done)).thenReturn(Optional.of(doneUser));
        when(users.findWithProfileById(failing)).thenReturn(Optional.of(failingUser));
        WeeklyGameReport existing = WeeklyGameReport.create("done-puuid", "VALORANT", WEEK_START);
        existing.update(WeeklyReportStatus.NO_DATA, null, null, null);
        when(reports.findByPuuidAndGameAndWeekStart("done-puuid", "VALORANT", WEEK_START))
                .thenReturn(Optional.of(existing));
        when(henrik.getCompetitiveMatchesSince(any(), eq(PUUID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        service.generateForActiveMembers();

        verify(henrik, never()).getCompetitiveMatchesSince(any(), eq("done-puuid"), any());
        ArgumentCaptor<WeeklyGameReport> saved = ArgumentCaptor.forClass(WeeklyGameReport.class);
        verify(reports).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WeeklyReportStatus.FAILED);
        verify(matchRecords, never()).saveAll(any());
        verifyNoInteractions(ai);
    }

    private void stubStoredMatches(List<ValorantMatchDetail> matches) {
        when(matchRecords.findByPuuidAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                PUUID, PERIOD.previousStartInstant(), PERIOD.endExclusive()))
                .thenReturn(matches.stream().map(match -> ValorantMatchRecord.of(PUUID, match)).toList());
    }

    @Test
    void missingDetailsKeepAvailableStatsAndPersistDiagnosticsWithoutFailingReport() {
        List<ValorantMatchDetail> available = List.of(
                detail("m1", "2026-11-04T00:00:00Z"), detail("m2", "2026-11-04T01:00:00Z"),
                detail("m3", "2026-11-07T00:00:00Z"), detail("m4", "2026-11-07T01:00:00Z"));
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(available);
        when(henrik.getCompetitiveMatchHistory(ValorantRegion.KR, PUUID)).thenReturn(List.of(
                new ValorantMatchReference("m1", Instant.parse("2026-11-04T00:00:00Z"), "Bind"),
                new ValorantMatchReference("missing1", Instant.parse("2026-11-02T14:00:00Z"), "Summit"),
                new ValorantMatchReference("missing2", Instant.parse("2026-11-02T15:27:00Z"), "Lotus"),
                new ValorantMatchReference("missing3", Instant.parse("2026-11-02T15:57:00Z"), "Summit"),
                new ValorantMatchReference("missing4", Instant.parse("2026-11-02T13:00:00Z"), "Bind"),
                new ValorantMatchReference("outside", Instant.parse("2026-10-25T14:00:00Z"), "Bind")));
        stubStoredMatches(available);

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.READY);
        assertThat(report.getStats().current().matches()).isEqualTo(4);
        assertThat(report.getStats().collectionIssues()).hasSize(4)
                .allSatisfy(issue -> assertThat(issue.httpStatus()).isEqualTo(404));
        verify(henrik, never()).getMatchDetail(any(), any(), eq("m1"));
        verify(henrik, never()).getMatchDetail(any(), any(), eq("outside"));
        when(henrik.findAccount("name", "TAG")).thenReturn(new ValorantAccount(PUUID, "name", "TAG"));
        WeeklyGameReportResponse admin = service.generateForRiotId("name#TAG", ValorantRegion.KR);
        assertThat(admin.notice()).contains("일부");
        assertThat(admin.collectionIssues()).hasSize(4);
        UUID memberId = UUID.randomUUID();
        when(memberships.isActive(memberId)).thenReturn(true);
        User memberUser = connectedUser(memberId);
        when(users.findWithProfileById(memberId)).thenReturn(Optional.of(memberUser));
        when(reports.findByPuuidAndGameAndWeekStart(PUUID, "VALORANT", WEEK_START)).thenReturn(Optional.of(report));
        WeeklyGameReportResponse member = service.getMyReport(memberId);
        assertThat(member.notice()).isEqualTo(admin.notice());
        assertThat(member.collectionIssues()).isNull();
    }

    @Test
    void recoversAnOlderMissingDetailWithoutDuplicatingAlreadyStoredMatches() {
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(List.of());
        when(henrik.getCompetitiveMatchHistory(ValorantRegion.KR, PUUID)).thenReturn(List.of(
                new ValorantMatchReference("stored", Instant.parse("2026-11-03T00:00:00Z"), "Bind"),
                new ValorantMatchReference("recovered", Instant.parse("2026-11-02T15:27:00Z"), "Lotus")));
        when(matchRecords.findExistingMatchIds(eq(PUUID), anyCollection())).thenReturn(List.of("stored"));
        ValorantMatchDetail recovered = detail("recovered", "2026-11-02T15:27:00Z");
        when(henrik.getMatchDetail(ValorantRegion.KR, PUUID, "recovered")).thenReturn(Optional.of(recovered));
        stubStoredMatches(List.of(recovered));

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStats().collectionIssues()).isEmpty();
        verify(henrik, never()).getMatchDetail(any(), any(), eq("stored"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ValorantMatchRecord>> saved = ArgumentCaptor.forClass(List.class);
        verify(matchRecords).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ValorantMatchRecord::getMatchId).containsExactly("recovered");
    }

    @Test
    void regenerationKeepsUnresolvedIssuesThatLeftRecentHistoryAndDropsRecoveredOnes() {
        WeeklyGameReport existing = WeeklyGameReport.create(PUUID, "VALORANT", WEEK_START);
        existing.update(WeeklyReportStatus.READY, new WeeklyStats(null, null, null, List.of(
                new WeeklyReportCollectionIssue("still-missing", Instant.parse("2026-11-02T14:00:00Z"), "Bind", 404, "없음"),
                new WeeklyReportCollectionIssue("recovered", Instant.parse("2026-11-02T15:00:00Z"), "Lotus", 404, "없음"))),
                null, null);
        when(reports.findByPuuidAndGameAndWeekStart(PUUID, "VALORANT", WEEK_START)).thenReturn(Optional.of(existing));
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(List.of());
        when(henrik.getCompetitiveMatchHistory(ValorantRegion.KR, PUUID)).thenReturn(List.of());
        when(henrik.getMatchDetail(ValorantRegion.KR, PUUID, "still-missing")).thenReturn(Optional.empty());
        ValorantMatchDetail recovered = detail("recovered", "2026-11-02T15:00:00Z");
        when(henrik.getMatchDetail(ValorantRegion.KR, PUUID, "recovered")).thenReturn(Optional.of(recovered));
        stubStoredMatches(List.of(recovered));

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStats().collectionIssues()).extracting(WeeklyReportCollectionIssue::matchId)
                .containsExactly("still-missing");
    }

    @Test
    void batchFailureDoesNotOverwriteReportCompletedMeanwhile() {
        UUID userId = UUID.randomUUID();
        when(users.findConnectedValorantUserIds()).thenReturn(List.of(userId));
        when(memberships.findActiveUserIds(List.of(userId))).thenReturn(Set.of(userId));
        User user = connectedUser(userId);
        when(users.findWithProfileById(userId)).thenReturn(Optional.of(user));
        WeeklyGameReport completed = WeeklyGameReport.create(PUUID, "VALORANT", WEEK_START);
        completed.update(WeeklyReportStatus.READY, null, null, null);
        // 배치 시작 때는 없고, 외부 API 실패 뒤 다시 보면 다른 생성이 끝나 있다.
        when(reports.findByPuuidAndGameAndWeekStart(PUUID, "VALORANT", WEEK_START))
                .thenReturn(Optional.empty(), Optional.of(completed));
        when(henrik.getCompetitiveMatchesSince(any(), eq(PUUID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        service.generateForActiveMembers();

        verify(reports, never()).save(any());
        assertThat(completed.getStatus()).isEqualTo(WeeklyReportStatus.READY);
    }

    @Test
    void historyFailureWarnsWithoutDiscardingAvailableStats() {
        when(henrik.getCompetitiveMatchesSince(any(), any(), any())).thenReturn(List.of());
        when(henrik.getCompetitiveMatchHistory(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limited"));
        stubStoredMatches(List.of(detail("stored", "2026-11-03T00:00:00Z")));

        WeeklyGameReport report = service.generate(PUUID, ValorantRegion.KR, PERIOD);

        assertThat(report.getStatus()).isEqualTo(WeeklyReportStatus.READY);
        assertThat(report.getStats().collectionIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.matchId()).isNull();
            assertThat(issue.httpStatus()).isEqualTo(429);
            assertThat(issue.reason()).contains("대조하지 못했습니다");
        });
    }

    private ValorantMatchDetail detail(String matchId, String startedAt) {
        return new ValorantMatchDetail(matchId, Instant.parse(startedAt), "Bind", "Jett", 12, "Gold 1",
                new ValorantMatchStats(20, 10, 5, 4800, 24, 8, 20, 2, true));
    }

    private User connectedUser(UUID userId) {
        return connectedUser(userId, PUUID);
    }

    private User connectedUser(UUID userId, String puuid) {
        UserProfile profile = mock(UserProfile.class);
        when(profile.hasConnectedValorant()).thenReturn(true);
        when(profile.getValorantPuuid()).thenReturn(puuid);
        when(profile.getValorantRegion()).thenReturn("KR");
        User user = mock(User.class);
        when(user.getProfile()).thenReturn(profile);
        return user;
    }
}
