package com.gamerin.backend.domain.pubg.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.pubg.client.PubgApiClient;
import com.gamerin.backend.domain.pubg.dto.response.PubgSummaryResponse;
import com.gamerin.backend.domain.pubg.exception.NoRankedRecordException;
import com.gamerin.backend.domain.pubg.model.NormalStats;
import com.gamerin.backend.domain.pubg.model.RankedStats;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PubgServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PubgApiClient pubgApiClient;

    private PubgService pubgService;

    @BeforeEach
    void setUp() {
        pubgService = new PubgService(userRepository, pubgApiClient);
    }

    @Test
    void getMySummaryFallsBackToNormalStatsWhenRankedStatsAreNotFound() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectPubg("PubgPlayer", "account-1");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season-1");
        when(pubgApiClient.getRankedStats("account-1", "season-1", "squad"))
                .thenThrow(new NoRankedRecordException());
        when(pubgApiClient.getNormalStats("account-1", "season-1", "squad"))
                .thenReturn(new NormalStats(1.26, 10, 2));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isTrue();
        assertThat(response.game()).isEqualTo("PUBG");
        assertThat(response.playerName()).isEqualTo("PubgPlayer");
        assertThat(response.tierLabel()).isNull();
        assertThat(response.kd()).isEqualTo(1.26);
        assertThat(response.winRate()).isEqualTo(20);
        assertThat(response.matches()).isEqualTo(10);
        assertThat(response.statsMode()).isEqualTo(GameStatsMode.NORMAL);

        Map<String, Object> stored = pubgStats(user.getProfile());
        assertThat(stored)
                .containsEntry("playerName", "PubgPlayer")
                .containsEntry("kd", 1.26)
                .containsEntry("matches", 10)
                .containsEntry("statsMode", "NORMAL");
        verify(pubgApiClient).getNormalStats("account-1", "season-1", "squad");
    }

    @Test
    void getMySummaryDoesNotFallBackToNormalStatsWhenRankedStatsRateLimited() {
        assertRankedFailureDoesNotFallBackToNormalStats(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void getMySummaryDoesNotFallBackToNormalStatsForUnclassifiedNotFound() {
        assertRankedFailureDoesNotFallBackToNormalStats(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMySummaryDoesNotFallBackToNormalStatsWhenRankedStatsGatewayFails() {
        assertRankedFailureDoesNotFallBackToNormalStats(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void getMySummaryReturnsRankedCommonContractAndTruncatesKdToTwoDecimalPlaces() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectPubg("PubgPlayer", "account-1");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season-1");
        when(pubgApiClient.getRankedStats("account-1", "season-1", "squad"))
                .thenReturn(new RankedStats(3.769230769230769, 16, 5, "Survivor", "1"));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.game()).isEqualTo("PUBG");
        assertThat(response.connected()).isTrue();
        assertThat(response.playerName()).isEqualTo("PubgPlayer");
        assertThat(response.tierLabel()).isEqualTo("Survivor 1");
        assertThat(response.kd()).isEqualTo(3.76);
        assertThat(response.winRate()).isEqualTo(31);
        assertThat(response.matches()).isEqualTo(16);
        assertThat(response.statsMode()).isEqualTo(GameStatsMode.RANKED);
    }

    @Test
    void getMySummaryKeepsUnavailableKdAndWinRateNull() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectPubg("PubgPlayer", "account-1");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season-1");
        when(pubgApiClient.getRankedStats("account-1", "season-1", "squad"))
                .thenReturn(new RankedStats(null, 16, null, "Gold", "III"));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.kd()).isNull();
        assertThat(response.winRate()).isNull();
        assertThat(pubgStats(user.getProfile()))
                .doesNotContainKeys("kd", "winRate")
                .containsEntry("statsMode", "RANKED");
    }

    @Test
    void getMySummaryLeavesStatsModeNullWhenNormalRecordHasNoMatches() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectPubg("PubgPlayer", "account-1");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season-1");
        when(pubgApiClient.getRankedStats("account-1", "season-1", "squad"))
                .thenThrow(new NoRankedRecordException());
        when(pubgApiClient.getNormalStats("account-1", "season-1", "squad"))
                .thenReturn(new NormalStats(null, 0, 0));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isTrue();
        assertThat(response.playerName()).isEqualTo("PubgPlayer");
        assertThat(response.statsMode()).isNull();
        assertThat(response.winRate()).isNull();
        assertThat(pubgStats(user.getProfile())).doesNotContainKey("statsMode");
    }

    @Test
    void getMySummaryReturnsNullableDisconnectedContract() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.game()).isEqualTo("PUBG");
        assertThat(response.connected()).isFalse();
        assertThat(response.playerName()).isNull();
        assertThat(response.tierLabel()).isNull();
        assertThat(response.kd()).isNull();
        assertThat(response.winRate()).isNull();
        assertThat(response.matches()).isNull();
        assertThat(response.statsMode()).isNull();
    }

    private void assertRankedFailureDoesNotFallBackToNormalStats(HttpStatus status) {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectPubg("PubgPlayer", "account-1");

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(pubgApiClient.findCurrentSeasonId()).thenReturn("season-1");
        when(pubgApiClient.getRankedStats("account-1", "season-1", "squad"))
                .thenThrow(new ResponseStatusException(status, "Ranked stats request failed."));

        assertThatThrownBy(() -> pubgService.getMySummary(CustomUserPrincipal.from(user)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
        verify(pubgApiClient, never()).getNormalStats("account-1", "season-1", "squad");
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pubgStats(UserProfile profile) {
        return (Map<String, Object>) profile.getGameStats().get("PUBG");
    }
}
