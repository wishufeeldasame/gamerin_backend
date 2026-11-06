package com.gamerin.backend.domain.valorant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.game.service.GameStatsPersistenceService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserProfileRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.valorant.client.HenrikValorantClient;
import com.gamerin.backend.domain.valorant.dto.request.ValorantConnectRequest;
import com.gamerin.backend.domain.valorant.dto.response.ValorantSummaryResponse;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.valorant.model.ValorantRank;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.gamerin.backend.domain.valorant.model.ValorantSummaryStats;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ValorantServiceTest {

    private static final ValorantRank GOLD = new ValorantRank("Gold 2", "Platinum 1", 40, null);

    @Mock
    private UserRepository userRepository;

    @Mock
    private HenrikValorantClient client;

    @Mock
    private UserProfileRepository userProfileRepository;

    private ValorantService service;

    @BeforeEach
    void setUp() {
        service = new ValorantService(userRepository, client, new GameStatsPersistenceService(userProfileRepository));
    }

    @Test
    void connectStoresAccountRankAndCompetitiveMetrics() {
        User user = savedUser();
        when(client.findAccount("Name", "KR1")).thenReturn(new ValorantAccount("henrik-puuid", "Name", "KR1"));
        when(client.getRank(ValorantRegion.KR, "henrik-puuid")).thenReturn(GOLD);
        when(client.getRecentCompetitiveMatches(ValorantRegion.KR, "henrik-puuid")).thenReturn(List.of(
                // kills, deaths, assists, score, rounds, head, body, leg, won
                new ValorantMatchStats(20, 10, 5, 5000, 20, 10, 30, 0, true),
                new ValorantMatchStats(10, 15, 3, 3000, 20, 5, 15, 0, false)
        ));

        var response = service.connect(CustomUserPrincipal.from(user),
                new ValorantConnectRequest(" Name#KR1 ", ValorantRegion.KR));

        assertThat(response.riotId()).isEqualTo("Name#KR1");
        UserProfile profile = user.getProfile();
        assertThat(profile.hasConnectedValorant()).isTrue();
        assertThat(profile.getValorantPuuid()).isEqualTo("henrik-puuid");
        assertThat(profile.getValorantRegion()).isEqualTo("KR");
        // K/D 30/25=1.2, ACS 8000/40=200, 헤드샷 15/60=25%, 승률 1/2=50%
        assertThat(profile.getValorantSummary())
                .isEqualTo(new ValorantSummaryStats("Gold 2", "Platinum 1", 40, 1.2, 200, 25, 50, 2));
    }

    @Test
    void unratedWithoutMatchesHasNoMetricsAndNoRr() {
        assertThat(ValorantService.summarize(new ValorantRank(null, null, 0, null), List.of()))
                .isEqualTo(new ValorantSummaryStats("Unrated", null, null, null, null, null, null, 0));
    }

    @Test
    void zeroDeathsUsesKillsAsKd() {
        ValorantSummaryStats summary = ValorantService.summarize(GOLD,
                List.of(new ValorantMatchStats(7, 0, 0, 0, 0, 0, 0, 0, true)));

        assertThat(summary.kd()).isEqualTo(7.0);
        assertThat(summary.acs()).isNull();
        assertThat(summary.headshotRate()).isNull();
        assertThat(summary.winRate()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NameOnly", "#KR1", "Name#", "  "})
    void connectRejectsMalformedRiotIdWithoutCallingApi(String riotId) {
        User user = savedUser();

        assertThatThrownBy(() -> service.connect(CustomUserPrincipal.from(user),
                new ValorantConnectRequest(riotId, ValorantRegion.KR)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        verify(client, never()).findAccount(any(), any());
    }

    @Test
    void connectRejectsAccountOwnedByAnotherUser() {
        User user = savedUser();
        when(client.findAccount("Name", "KR1")).thenReturn(new ValorantAccount("henrik-puuid", "Name", "KR1"));
        when(userRepository.existsConnectedValorantPuuidByOtherUser(user.getId(), "henrik-puuid")).thenReturn(true);

        assertThatThrownBy(() -> service.connect(CustomUserPrincipal.from(user),
                new ValorantConnectRequest("Name#KR1", ValorantRegion.KR)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(user.getProfile().hasConnectedValorant()).isFalse();
    }

    @Test
    void refreshSkipsWriteWhenConnectionChangedDuringFetch() {
        User user = savedUser();
        UserProfile profile = user.getProfile();
        profile.connectValorant("Name#KR1", "henrik-puuid", "KR", "PC",
                new ValorantSummaryStats("Gold 2", null, 40, null, null, null, null, 0), null);
        when(client.getRank(ValorantRegion.KR, "henrik-puuid")).thenAnswer(invocation -> {
            profile.disconnectValorant();
            return new ValorantRank("Diamond 1", null, 10, null);
        });
        when(client.getRecentCompetitiveMatches(ValorantRegion.KR, "henrik-puuid")).thenReturn(List.of());

        ValorantSummaryResponse response = service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isFalse();
        assertThat(profile.getGameStats()).doesNotContainKey("VALORANT");
    }

    @Test
    void refreshUpdatesStoredSummary() {
        User user = savedUser();
        user.getProfile().connectValorant("Name#KR1", "henrik-puuid", "AP", "PC",
                new ValorantSummaryStats("Gold 2", null, 40, null, null, null, null, 0), null);
        when(client.getRank(ValorantRegion.AP, "henrik-puuid")).thenReturn(new ValorantRank("Diamond 1", "Diamond 1", 10, null));
        when(client.getRecentCompetitiveMatches(ValorantRegion.AP, "henrik-puuid")).thenReturn(List.of());

        ValorantSummaryResponse response = service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.tierLabel()).isEqualTo("Diamond 1");
        assertThat(response.peakTierLabel()).isEqualTo("Diamond 1");
        assertThat(response.region()).isEqualTo("AP");
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void refreshWithinCooldownReturnsStoredSummaryWithoutApiCalls() {
        User user = savedUser();
        OffsetDateTime updatedAt = OffsetDateTime.now().minusMinutes(9);
        user.getProfile().connectValorant("Name#KR1", "henrik-puuid", "KR", "PC",
                new ValorantSummaryStats("Gold 2", null, 40, null, null, null, null, 0), updatedAt);

        ValorantSummaryResponse response = service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.tierLabel()).isEqualTo("Gold 2");
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        verifyNoInteractions(client);
    }

    @Test
    void refreshAfterCooldownCallsApi() {
        User user = savedUser();
        user.getProfile().connectValorant("Name#KR1", "henrik-puuid", "KR", "PC",
                new ValorantSummaryStats("Gold 2", null, 40, null, null, null, null, 0),
                OffsetDateTime.now().minus(ValorantService.REFRESH_COOLDOWN).minusSeconds(1));
        when(client.getRank(ValorantRegion.KR, "henrik-puuid")).thenReturn(new ValorantRank("Gold 3", null, 5, null));
        when(client.getRecentCompetitiveMatches(ValorantRegion.KR, "henrik-puuid")).thenReturn(List.of());

        assertThat(service.refreshMySummary(CustomUserPrincipal.from(user)).tierLabel()).isEqualTo("Gold 3");
    }

    @Test
    void refreshAppliesChangedRiotIdAndKeepsItWhenAbsent() {
        User user = savedUser();
        UserProfile profile = user.getProfile();
        profile.connectValorant("Old#KR1", "henrik-puuid", "KR", "PC",
                new ValorantSummaryStats("Gold 2", null, 40, null, null, null, null, 0), null);
        when(client.getRank(ValorantRegion.KR, "henrik-puuid"))
                .thenReturn(new ValorantRank("Gold 2", null, 40, "New#KR9"))
                .thenReturn(new ValorantRank("Gold 2", null, 40, null));
        when(client.getRecentCompetitiveMatches(ValorantRegion.KR, "henrik-puuid")).thenReturn(List.of());

        assertThat(service.refreshMySummary(CustomUserPrincipal.from(user)).riotId()).isEqualTo("New#KR9");

        ReflectionTestUtils.setField(profile, "gameStats", withoutUpdatedAt(profile.getGameStats()));
        assertThat(service.refreshMySummary(CustomUserPrincipal.from(user)).riotId()).isEqualTo("New#KR9");
    }

    @Test
    void summaryOfDisconnectedUserIsNotConnected() {
        User user = savedUser();

        assertThat(service.getMySummary(CustomUserPrincipal.from(user)).connected()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> withoutUpdatedAt(java.util.Map<String, Object> gameStats) {
        java.util.Map<String, Object> next = new java.util.HashMap<>(gameStats);
        java.util.Map<String, Object> valorant = new java.util.HashMap<>((java.util.Map<String, Object>) next.get("VALORANT"));
        valorant.remove("updatedAt");
        next.put("VALORANT", valorant);
        return next;
    }

    private User savedUser() {
        UUID id = UUID.randomUUID();
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        lenient().when(userRepository.findWithProfileById(id)).thenReturn(Optional.of(user));
        lenient().when(userProfileRepository.findByUserIdForUpdate(id)).thenReturn(Optional.of(profile));
        return user;
    }
}
