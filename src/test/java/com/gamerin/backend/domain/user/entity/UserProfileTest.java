package com.gamerin.backend.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"old-account", " ", "NEW-ACCOUNT"})
    void riotReplacementDiscardsCacheWhoseOwnerDoesNotMatch(String oldAccount) {
        UserProfile profile = savedUser().getProfile();
        profile.connectRiot("Old#KR1", oldAccount);
        profile.updateLolSummary("GOLD I", 2.5, 60, 100);
        profile.connectPubg("Other", "pubg-account");
        Map<String, Object> before = profile.getGameStats();
        long version = profile.getGameConnectionVersion("RIOT");

        profile.connectRiot("New#KR1", "new-account");

        assertThat(profile.getRiotPuuid()).isEqualTo("new-account");
        assertThat(profile.getGameStats()).doesNotContainKey("LOL");
        assertThat(profile.getGameStats().get("PUBG")).isEqualTo(before.get("PUBG"));
        assertThat(before).containsKey("LOL");
        assertThat(profile.getGameConnectionVersion("RIOT")).isEqualTo(version + 1);
    }

    @Test
    void riotRenamePreservesCacheForSameAccount() {
        UserProfile profile = savedUser().getProfile();
        profile.connectRiot("Old#KR1", "account");
        profile.updateLolSummary("GOLD I", 2.5, 60, 100);
        Object cached = profile.getGameStats().get("LOL");
        long version = profile.getGameConnectionVersion("RIOT");

        profile.connectRiot("Renamed#KR2", "account");

        assertThat(profile.getRiotId()).isEqualTo("Renamed#KR2");
        assertThat(profile.getGameStats().get("LOL")).isEqualTo(cached);
        assertThat(profile.getGameConnectionVersion("RIOT")).isEqualTo(version + 1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"old-account", " ", "NEW-ACCOUNT"})
    void pubgReplacementDiscardsPreviousAccountStatistics(String oldAccount) {
        UserProfile profile = savedUser().getProfile();
        profile.connectPubg("Old", oldAccount);
        profile.updatePubgSummary("Gold", 2.5, 60, 100, GameStatsMode.RANKED);
        profile.connectRiot("Other#KR1", "riot-account");
        profile.updateLolSummary("Silver", 1.0, 40, 10);
        Map<String, Object> before = profile.getGameStats();
        long version = profile.getGameConnectionVersion("PUBG");

        profile.connectPubg("New", "new-account");

        assertThat(nestedMap(profile.getGameStats(), "PUBG")).containsExactlyInAnyOrderEntriesOf(
                Map.of("connected", true, "playerName", "New", "accountId", "new-account"));
        assertThat(profile.getGameStats().get("RIOT")).isEqualTo(before.get("RIOT"));
        assertThat(profile.getGameStats().get("LOL")).isEqualTo(before.get("LOL"));
        assertThat(nestedMap(before, "PUBG")).containsEntry("tierLabel", "Gold");
        assertThat(profile.getGameConnectionVersion("PUBG")).isEqualTo(version + 1);
    }

    @Test
    void pubgRenamePreservesCacheForSameAccount() {
        UserProfile profile = savedUser().getProfile();
        profile.connectPubg("Old", "account");
        profile.updatePubgSummary("Gold", 2.5, 60, 100, GameStatsMode.RANKED);
        Map<String, Object> expected = new HashMap<>(nestedMap(profile.getGameStats(), "PUBG"));
        expected.put("playerName", "Renamed");
        long version = profile.getGameConnectionVersion("PUBG");

        profile.connectPubg("Renamed", "account");

        assertThat(nestedMap(profile.getGameStats(), "PUBG")).isEqualTo(expected);
        assertThat(profile.getGameConnectionVersion("PUBG")).isEqualTo(version + 1);
    }

    @Test
    void connectR6PreservesExistingPubgData() {
        User user = savedUser();
        UserProfile profile = user.getProfile();
        profile.connectPubg("pubgPlayer", "pubg-account");

        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Gold",
                1.2,
                52,
                100,
                GameStatsMode.RANKED,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        assertThat(profile.getGameStats()).containsKeys("PUBG", "R6");
        assertThat(profile.getPubgAccountId()).isEqualTo("pubg-account");
    }

    @Test
    void r6LifecyclePreservesExistingRiotAndLolData() {
        UserProfile profile = savedUser().getProfile();
        profile.updateGameStats(new HashMap<>(Map.of(
                "RIOT", Map.of("connected", true, "riotId", "Player#KR1"),
                "LOL", Map.of("tierLabel", "Gold")
        )));

        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Silver",
                1.0,
                50,
                20,
                GameStatsMode.RANKED,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        profile.updateR6Summary(
                "Gold",
                1.2,
                55,
                30,
                GameStatsMode.RANKED,
                OffsetDateTime.parse("2026-07-11T12:00:00+09:00")
        );
        profile.disconnectR6();

        assertThat(profile.getGameStats()).doesNotContainKey("R6");
        assertThat(nestedMap(profile.getGameStats(), "RIOT"))
                .containsEntry("connected", true)
                .containsEntry("riotId", "Player#KR1");
        assertThat(nestedMap(profile.getGameStats(), "LOL"))
                .containsEntry("tierLabel", "Gold");
    }

    @Test
    void updateR6SummaryPreservesPlayerNamePlatformAndIdentifiers() {
        UserProfile profile = savedUser().getProfile();
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Silver",
                1.0,
                40,
                20,
                GameStatsMode.RANKED,
                OffsetDateTime.parse("2026-07-09T12:00:00+09:00")
        );

        profile.updateR6Summary(
                "Emerald",
                1.5,
                60,
                150,
                GameStatsMode.RANKED,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        assertThat(profile.getR6PlayerName()).isEqualTo("R6Player");
        assertThat(profile.getR6ConnectedPlatform()).isEqualTo("PC");
        assertThat(profile.getR6AccountId()).isEqualTo("account-1");
        assertThat(profile.getR6TierLabel()).isEqualTo("Emerald");
        assertThat(profile.getR6Kd()).isEqualTo(1.5);
        assertThat(profile.getR6WinRate()).isEqualTo(60);
        assertThat(profile.getR6Matches()).isEqualTo(150);
        assertThat(profile.getR6StatsMode()).isEqualTo(GameStatsMode.RANKED);
    }

    @Test
    void disconnectR6PreservesOtherGameData() {
        UserProfile profile = savedUser().getProfile();
        profile.updateGameStats(new HashMap<>(Map.of("OTHER", Map.of("connected", true))));
        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        profile.disconnectR6();

        assertThat(profile.getGameStats()).doesNotContainKey("R6");
        assertThat(profile.getGameStats()).containsKey("OTHER");
    }

    @Test
    void r6OperationsHandleNullOrNonMapGameStatsSafely() {
        UserProfile profile = savedUser().getProfile();
        profile.updateGameStats(null);

        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        assertThat(profile.hasConnectedR6()).isTrue();

        profile.updateGameStats(new HashMap<>(Map.of("R6", "not-a-map", "PUBG", Map.of("playerName", "pubg"))));
        profile.disconnectR6();

        assertThat(profile.getGameStats()).doesNotContainKey("R6");
        assertThat(profile.getGameStats()).containsKey("PUBG");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private User savedUser() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }
}
