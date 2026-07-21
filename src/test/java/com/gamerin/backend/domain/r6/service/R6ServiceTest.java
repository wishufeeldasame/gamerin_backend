package com.gamerin.backend.domain.r6.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.r6.client.R6StatsClient;
import com.gamerin.backend.domain.r6.dto.request.R6ConnectRequest;
import com.gamerin.backend.domain.r6.dto.response.R6ConnectionResponse;
import com.gamerin.backend.domain.r6.dto.response.R6SummaryResponse;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;
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
class R6ServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private R6StatsClient r6StatsClient;

    private R6Service r6Service;

    @BeforeEach
    void setUp() {
        r6Service = new R6Service(userRepository, r6StatsClient);
    }

    @Test
    void connectRejectsMissingPrincipal() {
        assertThatThrownBy(() -> r6Service.connect(null, new R6ConnectRequest("PlayerOne")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void connectRejectsMissingAuthenticatedUser() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> r6Service.connect(CustomUserPrincipal.from(user), new R6ConnectRequest("PlayerOne")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void connectRejectsMissingUserProfile() {
        User user = savedUserWithoutProfile(UUID.randomUUID(), "tester", "Tester");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> r6Service.connect(CustomUserPrincipal.from(user), new R6ConnectRequest("PlayerOne")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void connectReturns503WhenStatsClientIsNotConfigured() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.updateGameStats(new HashMap<>(Map.of("PUBG", Map.of("playerName", "pubgPlayer"))));
        Map<String, Object> before = deepCopy(profile.getGameStats());

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.findProfile("PlayerOne"))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "R6 stats API is not configured."));

        assertThatThrownBy(() -> r6Service.connect(CustomUserPrincipal.from(user), new R6ConnectRequest("PlayerOne")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(profile.getGameStats()).isEqualTo(before);
    }

    @Test
    void connectFailureDoesNotChangeGameStats() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.updateGameStats(new HashMap<>(Map.of("PUBG", Map.of("playerName", "pubgPlayer"))));
        Map<String, Object> before = deepCopy(profile.getGameStats());

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.findProfile("MissingPlayer"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "R6 public profile not found."));

        assertThatThrownBy(() -> r6Service.connect(CustomUserPrincipal.from(user), new R6ConnectRequest("MissingPlayer")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(profile.getGameStats()).isEqualTo(before);
    }

    @Test
    void connectStoresR6DataAfterPublicProfileLookupSucceeds() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.updateGameStats(new HashMap<>(Map.of("PUBG", Map.of("playerName", "pubgPlayer"))));

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.findProfile("R6Player")).thenReturn(new R6Profile(
                "R6Player",
                "account-1",
                new R6SummaryStats("Gold", 1.239, 52.4, 120)
        ));

        R6ConnectionResponse response = r6Service.connect(
                CustomUserPrincipal.from(user),
                new R6ConnectRequest("  R6Player  ")
        );

        assertThat(response.connected()).isTrue();
        assertThat(response.playerName()).isEqualTo("R6Player");
        assertThat(response.platform()).isEqualTo("PC");

        Map<String, Object> r6 = r6Stats(profile);
        assertThat(r6)
                .containsEntry("connected", true)
                .containsEntry("playerName", "R6Player")
                .containsEntry("playerNameNormalized", "r6player")
                .containsEntry("platform", "PC")
                .containsEntry("accountId", "account-1")
                .containsEntry("tierLabel", "Gold")
                .containsEntry("kd", 1.23)
                .containsEntry("winRate", 52.0)
                .containsEntry("matches", 120);
        assertThat(r6.get("updatedAt")).isInstanceOf(String.class);
        assertThat(profile.getGameStats()).containsKey("PUBG");
    }

    @Test
    void connectRejectsProfileWithoutInternalIdentifier() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        Map<String, Object> before = deepCopy(profile.getGameStats());

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.findProfile("R6Player")).thenReturn(new R6Profile(
                "R6Player",
                null,
                null
        ));

        assertThatThrownBy(() -> r6Service.connect(CustomUserPrincipal.from(user), new R6ConnectRequest("R6Player")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(profile.getGameStats()).isEqualTo(before);
    }

    @Test
    void disconnectRemovesOnlyR6DataAndPreservesPubgData() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.connectPubg("pubgPlayer", "pubg-account");
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Gold",
                1.2,
                50.0,
                10,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        r6Service.disconnect(CustomUserPrincipal.from(user));

        assertThat(profile.getGameStats()).doesNotContainKey("R6");
        assertThat(profile.getGameStats()).containsKey("PUBG");
    }

    @Test
    void getMySummaryReturnsDisconnectedResponseWhenNotConnected() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        R6SummaryResponse response = r6Service.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isFalse();
        assertThat(response.game()).isEqualTo("R6");
        assertThat(response.platform()).isEqualTo("PC");
        assertThat(response.playerName()).isNull();
        verifyNoInteractions(r6StatsClient);
    }

    @Test
    void getMySummaryReturnsStoredSummaryWithoutExternalCallOrMutation() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        OffsetDateTime storedUpdatedAt = OffsetDateTime.parse("2026-07-09T12:00:00+09:00");
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Silver",
                1.0,
                40.0,
                20,
                storedUpdatedAt
        );
        Map<String, Object> before = deepCopy(profile.getGameStats());

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        R6SummaryResponse response = r6Service.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isTrue();
        assertThat(response.playerName()).isEqualTo("R6Player");
        assertThat(response.platform()).isEqualTo("PC");
        assertThat(response.tierLabel()).isEqualTo("Silver");
        assertThat(response.kd()).isEqualTo(1.0);
        assertThat(response.winRate()).isEqualTo(40.0);
        assertThat(response.matches()).isEqualTo(20);
        assertThat(response.updatedAt()).isEqualTo(storedUpdatedAt);
        assertThat(profile.getGameStats()).isEqualTo(before);
        verifyNoInteractions(r6StatsClient);
    }

    @Test
    void getMySummaryReturnsDisconnectedWhenAccountIdIsMissingWithoutExternalCall() {
        User user = connectedUserWithoutAccountId();
        UserProfile profile = user.getProfile();
        Map<String, Object> before = deepCopy(profile.getGameStats());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        R6SummaryResponse response = r6Service.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isFalse();
        assertThat(profile.getGameStats()).isEqualTo(before);
        verifyNoInteractions(r6StatsClient);
    }

    @Test
    void refreshMySummaryReturnsDisconnectedWhenNotConnectedWithoutExternalCall() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        R6SummaryResponse response = r6Service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isFalse();
        verifyNoInteractions(r6StatsClient);
    }

    @Test
    void refreshMySummaryReturnsDisconnectedWhenAccountIdIsMissingWithoutExternalCall() {
        User user = connectedUserWithoutAccountId();
        UserProfile profile = user.getProfile();
        Map<String, Object> before = deepCopy(profile.getGameStats());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        R6SummaryResponse response = r6Service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isFalse();
        assertThat(profile.getGameStats()).isEqualTo(before);
        verifyNoInteractions(r6StatsClient);
    }

    @Test
    void refreshMySummaryUpdatesStoredSummaryAndReturnsPublicFields() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Silver",
                1.0,
                40.0,
                20,
                OffsetDateTime.parse("2026-07-09T12:00:00+09:00")
        );

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.getSummary(new R6ProfileRef("R6Player", "account-1")))
                .thenReturn(new R6SummaryStats("Platinum", 1.459, 58.6, 130));

        R6SummaryResponse response = r6Service.refreshMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isTrue();
        assertThat(response.playerName()).isEqualTo("R6Player");
        assertThat(response.platform()).isEqualTo("PC");
        assertThat(response.tierLabel()).isEqualTo("Platinum");
        assertThat(response.kd()).isEqualTo(1.45);
        assertThat(response.winRate()).isEqualTo(59.0);
        assertThat(response.matches()).isEqualTo(130);
        assertThat(response.updatedAt()).isNotNull();

        Map<String, Object> r6 = r6Stats(profile);
        assertThat(r6)
                .containsEntry("playerName", "R6Player")
                .containsEntry("platform", "PC")
                .containsEntry("accountId", "account-1")
                .containsEntry("tierLabel", "Platinum")
                .containsEntry("kd", 1.45)
                .containsEntry("winRate", 59.0)
                .containsEntry("matches", 130);
    }

    @Test
    void refreshMySummaryPropagatesExternal404AndPreservesStoredStats() {
        assertSummaryFailurePreservesStats(HttpStatus.NOT_FOUND);
    }

    @Test
    void refreshMySummaryPropagatesExternal429AndPreservesStoredStats() {
        assertSummaryFailurePreservesStats(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void refreshMySummaryPropagatesNetworkFailureAndPreservesStoredStats() {
        assertSummaryFailurePreservesStats(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void refreshMySummaryPropagatesAccountMismatchAndPreservesStoredStats() {
        assertSummaryFailurePreservesStats(HttpStatus.CONFLICT);
    }

    @Test
    void r6ResponsesDoNotExposeInternalIdentifiers() {
        assertThat(recordComponentNames(R6ConnectionResponse.class))
                .doesNotContain("accountId");
        assertThat(recordComponentNames(R6SummaryResponse.class))
                .doesNotContain("accountId");
    }

    private void assertSummaryFailurePreservesStats(HttpStatus status) {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        UserProfile profile = user.getProfile();
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Silver",
                1.0,
                40.0,
                20,
                OffsetDateTime.parse("2026-07-09T12:00:00+09:00")
        );
        Map<String, Object> before = deepCopy(profile.getGameStats());

        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(r6StatsClient.getSummary(new R6ProfileRef("R6Player", "account-1")))
                .thenThrow(new ResponseStatusException(status, "external failure"));

        assertThatThrownBy(() -> r6Service.refreshMySummary(CustomUserPrincipal.from(user)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(status.value());
        assertThat(profile.getGameStats()).isEqualTo(before);
    }

    private User connectedUserWithoutAccountId() {
        User user = savedUser(UUID.randomUUID(), "tester", "Tester");
        user.getProfile().connectR6(
                "R6Player",
                "r6player",
                "PC",
                null,
                "Silver",
                1.0,
                40.0,
                20,
                OffsetDateTime.parse("2026-07-09T12:00:00+09:00")
        );
        return user;
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = savedUserWithoutProfile(id, handle, nickname);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }

    private User savedUserWithoutProfile(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> r6Stats(UserProfile profile) {
        return (Map<String, Object>) profile.getGameStats().get("R6");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), new HashMap<>((Map<String, Object>) map));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private java.util.List<String> recordComponentNames(Class<?> recordType) {
        return java.util.Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
