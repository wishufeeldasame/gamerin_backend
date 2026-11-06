package com.gamerin.backend.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.valorant.model.ValorantSummaryStats;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserProfileValorantTest {

    private static final ValorantSummaryStats GOLD =
            new ValorantSummaryStats("Gold 2", "Platinum 1", 40, 1.25, 230, 24, 60, 10);

    @Test
    void summaryUpdateKeepsAccountFieldsAndReplacesStats() {
        UserProfile profile = profile();
        profile.connectValorant("name#KR1", "henrik-puuid", "KR", "PC", GOLD, null);
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-11-06T12:00:00+09:00");

        profile.updateValorantSummary(null,
                new ValorantSummaryStats("Unrated", "Platinum 1", null, null, null, null, null, 0), updatedAt);

        assertThat(profile.getValorantPuuid()).isEqualTo("henrik-puuid");
        assertThat(profile.getValorantRiotId()).isEqualTo("name#KR1");
        assertThat(profile.getValorantRegion()).isEqualTo("KR");
        assertThat(profile.getValorantSummary())
                .isEqualTo(new ValorantSummaryStats("Unrated", "Platinum 1", null, null, null, null, null, 0));
        assertThat(profile.getValorantUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void disconnectRemovesValorantAndAdvancesOnlyItsVersion() {
        UserProfile profile = profile();
        profile.connectRiot("name#KR1", "riot-puuid");
        profile.connectValorant("name#KR1", "henrik-puuid", "KR", "PC", GOLD, null);
        long riotVersion = profile.getGameConnectionVersion("RIOT");
        long valorantVersion = profile.getGameConnectionVersion("VALORANT");

        profile.disconnectValorant();

        assertThat(profile.hasConnectedValorant()).isFalse();
        assertThat(profile.getGameStats()).doesNotContainKey("VALORANT").containsKey("RIOT");
        assertThat(profile.getGameConnectionVersion("VALORANT")).isEqualTo(valorantVersion + 1);
        assertThat(profile.getGameConnectionVersion("RIOT")).isEqualTo(riotVersion);
    }

    @Test
    void riotDisconnectDoesNotTouchValorant() {
        UserProfile profile = profile();
        profile.connectRiot("name#KR1", "riot-puuid");
        profile.connectValorant("name#KR1", "henrik-puuid", "AP", "PC", GOLD, null);

        profile.disconnectRiot();

        assertThat(profile.hasConnectedGame(GameType.VALORANT)).isTrue();
        assertThat(profile.getValorantSummary()).isEqualTo(GOLD);
    }

    @Test
    void reconnectDropsPreviousAccountStats() {
        UserProfile profile = profile();
        profile.connectValorant("old#KR1", "old-puuid", "KR", "PC", GOLD, null);

        profile.connectValorant("new#KR1", "new-puuid", "AP", "PC",
                new ValorantSummaryStats("Unrated", null, null, null, null, null, null, 0), null);

        assertThat(profile.getValorantPuuid()).isEqualTo("new-puuid");
        assertThat(profile.getValorantSummary().peakTierLabel()).isNull();
        assertThat(profile.getValorantSummary().kd()).isNull();
        assertThat(profile.getValorantRegion()).isEqualTo("AP");
    }

    private UserProfile profile() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return profile;
    }
}
