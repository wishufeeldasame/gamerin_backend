package com.gamerin.backend.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.user.dto.response.RepresentativeTierResponse;

class UserProfileRepresentativeGameTest {

    @Test
    void eachConnectedGameExposesItsStoredTierLabelAsIs() {
        UserProfile profile = connectedProfile();

        profile.changeRepresentativeGame(GameType.PUBG);
        assertThat(profile.getRepresentativeTierLabel()).isEqualTo("Gold III");
        profile.changeRepresentativeGame(GameType.R6);
        assertThat(profile.getRepresentativeTierLabel()).isEqualTo("EMERALD II");
        profile.changeRepresentativeGame(GameType.LOL);
        assertThat(profile.getRepresentativeTierLabel()).isEqualTo("GOLD I");
        assertThat(RepresentativeTierResponse.from(profile))
                .isEqualTo(new RepresentativeTierResponse(GameType.LOL, "GOLD I"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNRANKED", "unranked"})
    void unrankedLolHasNoRepresentativeTier(String label) {
        UserProfile profile = connectedProfile();
        profile.updateLolSummary(label, 1.0, 50, 10);
        profile.changeRepresentativeGame(GameType.LOL);

        assertThat(profile.getRepresentativeTierLabel()).isNull();
        assertThat(RepresentativeTierResponse.from(profile)).isNull();
    }

    @Test
    void missingTierOrNoSelectionHasNoRepresentativeTier() {
        UserProfile profile = connectedProfile();
        profile.updatePubgSummary(null, 1.0, 10, 5, GameStatsMode.NORMAL);

        assertThat(RepresentativeTierResponse.from(profile)).isNull();
        profile.changeRepresentativeGame(GameType.PUBG);
        assertThat(RepresentativeTierResponse.from(profile)).isNull();
        assertThat(RepresentativeTierResponse.from(null)).isNull();
    }

    @Test
    void connectionCheckFollowsTheGameKeys() {
        UserProfile profile = profile();
        assertThat(profile.hasConnectedGame(GameType.LOL)).isFalse();

        profile.connectRiot("name#KR1", "puuid");

        assertThat(profile.hasConnectedGame(GameType.LOL)).isTrue();
        // 발로란트는 Riot 연동과 별개다.
        assertThat(profile.hasConnectedGame(GameType.VALORANT)).isFalse();
        assertThat(profile.hasConnectedGame(GameType.PUBG)).isFalse();
        assertThat(profile.hasConnectedGame(GameType.OVERWATCH)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Gold 2", "Radiant"})
    void valorantTierComesFromItsOwnConnection(String label) {
        UserProfile profile = connectedProfile();
        assertThat(profile.hasConnectedGame(GameType.VALORANT)).isFalse();

        putValorantTier(profile, label);
        profile.changeRepresentativeGame(GameType.VALORANT);

        assertThat(RepresentativeTierResponse.from(profile))
                .isEqualTo(new RepresentativeTierResponse(GameType.VALORANT, label));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unrated", "UNRANKED"})
    void unratedValorantHasNoRepresentativeTier(String label) {
        UserProfile profile = connectedProfile();
        profile.changeRepresentativeGame(GameType.VALORANT);
        putValorantTier(profile, label);

        assertThat(RepresentativeTierResponse.from(profile)).isNull();
    }

    @Test
    void disconnectingTheRepresentativeGameClearsIt() {
        assertClearedBy(GameType.PUBG, UserProfile::disconnectPubg);
        assertClearedBy(GameType.R6, UserProfile::disconnectR6);
        assertClearedBy(GameType.LOL, UserProfile::disconnectRiot);
        assertClearedBy(GameType.LOL, UserProfile::clearForWithdrawal);
    }

    @Test
    void disconnectingAnotherGameKeepsTheRepresentativeGame() {
        UserProfile profile = connectedProfile();
        profile.changeRepresentativeGame(GameType.LOL);

        profile.disconnectPubg();
        profile.disconnectR6();

        assertThat(profile.getRepresentativeGame()).isEqualTo(GameType.LOL);
        assertThat(profile.getRepresentativeTierLabel()).isEqualTo("GOLD I");
    }

    @Test
    void disconnectingRiotKeepsTheValorantRepresentative() {
        UserProfile profile = connectedProfile();
        putValorantTier(profile, "Gold 2");
        profile.changeRepresentativeGame(GameType.VALORANT);

        profile.disconnectRiot();

        assertThat(profile.getRepresentativeGame()).isEqualTo(GameType.VALORANT);
        assertThat(profile.getRepresentativeTierLabel()).isEqualTo("Gold 2");
    }

    private void assertClearedBy(GameType game, Consumer<UserProfile> action) {
        UserProfile profile = connectedProfile();
        profile.changeRepresentativeGame(game);

        action.accept(profile);

        assertThat(profile.getRepresentativeGame()).isNull();
    }

    // 발로란트 연동·요약 저장은 #178에서 구현한다. 그 전까지는 저장될 형태를 직접 넣어 검증한다.
    private void putValorantTier(UserProfile profile, String tierLabel) {
        Map<String, Object> gameStats = new HashMap<>(profile.getGameStats());
        gameStats.put(GameType.VALORANT.name(), Map.of("connected", true, "tierLabel", tierLabel));
        profile.updateGameStats(gameStats);
    }

    private UserProfile connectedProfile() {
        UserProfile profile = profile();
        profile.connectPubg("pubgPlayer", "account.pubg");
        profile.updatePubgSummary("Gold III", 1.2, 10, 30, GameStatsMode.RANKED);
        profile.connectR6("r6Player", "r6player", "uplay", "r6-account", "EMERALD II", 1.1, 55, 40,
                GameStatsMode.RANKED, null);
        profile.connectRiot("name#KR1", "puuid");
        profile.updateLolSummary("GOLD I", 3.0, 52, 100);
        return profile;
    }

    private UserProfile profile() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return profile;
    }
}
