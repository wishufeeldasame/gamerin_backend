package com.gamerin.backend.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileTest {

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
                52.0,
                100,
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
                50.0,
                20,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        profile.updateR6Summary(
                "Gold",
                1.2,
                55.0,
                30,
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
                40.0,
                20,
                OffsetDateTime.parse("2026-07-09T12:00:00+09:00")
        );

        profile.updateR6Summary(
                "Emerald",
                1.5,
                60.0,
                150,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        assertThat(profile.getR6PlayerName()).isEqualTo("R6Player");
        assertThat(profile.getR6ConnectedPlatform()).isEqualTo("PC");
        assertThat(profile.getR6AccountId()).isEqualTo("account-1");
        assertThat(profile.getR6TierLabel()).isEqualTo("Emerald");
        assertThat(profile.getR6Kd()).isEqualTo(1.5);
        assertThat(profile.getR6WinRate()).isEqualTo(60.0);
        assertThat(profile.getR6Matches()).isEqualTo(150);
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
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        assertThat(profile.hasConnectedR6()).isTrue();

        profile.updateGameStats(new HashMap<>(Map.of("R6", "not-a-map", "PUBG", Map.of("playerName", "pubg"))));
        profile.disconnectR6();

        assertThat(profile.getGameStats()).doesNotContainKey("R6");
        assertThat(profile.getGameStats()).containsKey("PUBG");
    }

    @Test
    void connectR6RemovesLegacyTrackerIdentifierWithoutChangingOtherGameData() {
        UserProfile profile = savedUser().getProfile();
        Map<String, Object> legacyR6 = new HashMap<>();
        legacyR6.put("connected", true);
        legacyR6.put("trackerProfileId", "legacy-tracker-id");
        profile.updateGameStats(new HashMap<>(Map.of(
                "R6", legacyR6,
                "PUBG", Map.of("playerName", "pubgPlayer")
        )));

        profile.connectR6(
                "R6Player",
                "r6player",
                "PC",
                "account-1",
                "Gold",
                1.2,
                52.0,
                100,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );

        assertThat(nestedMap(profile.getGameStats(), "R6"))
                .containsEntry("accountId", "account-1")
                .doesNotContainKey("trackerProfileId");
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
