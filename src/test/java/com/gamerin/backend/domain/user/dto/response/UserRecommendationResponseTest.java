package com.gamerin.backend.domain.user.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;

class UserRecommendationResponseTest {

    @Test
    void missingProfileAndUnselectedOrDisconnectedGamesHaveNoStats() {
        User user = User.createLocal("user@example.com", "user", "User", "hash");
        var missing = UserRecommendationResponse.from(user, 0, false);
        assertThat(missing.profileImageUrl()).isNull();
        assertThat(missing.representativeTier()).isNull();
        assertThat(missing.winRate()).isNull();

        user.setProfile(UserProfile.createDefault(user));
        user.getProfile().updateGameStats(Map.of("PUBG", Map.of("connected", true, "tierLabel", "Gold", "winRate", 60)));
        assertThat(UserRecommendationResponse.from(user, 0, false).winRate()).isNull();
        user.getProfile().changeRepresentativeGame(GameType.PUBG);
        assertThat(UserRecommendationResponse.from(user, 0, false).winRate()).isEqualTo(60.0);
        user.getProfile().updateGameStats(Map.of("PUBG", Map.of("connected", false, "tierLabel", "Gold", "winRate", 60)));
        assertThat(UserRecommendationResponse.from(user, 0, false).representativeTier()).isNull();
        assertThat(UserRecommendationResponse.from(user, 0, false).winRate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 58.5, 100, -1, 101, Double.NaN, Double.POSITIVE_INFINITY})
    void onlyFinitePercentageWinRatesAreExposed(double winRate) {
        User user = User.createLocal("user@example.com", "user", "User", "hash");
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        profile.updateGameStats(Map.of("R6", Map.of("connected", true, "tierLabel", "Gold", "winRate", winRate)));
        profile.changeRepresentativeGame(GameType.R6);

        Double expected = Double.isFinite(winRate) && winRate >= 0 && winRate <= 100 ? winRate : null;
        assertThat(UserRecommendationResponse.from(user, 2, true).winRate()).isEqualTo(expected);
        profile.updateGameStats(Map.of("R6", Map.of("connected", true, "tierLabel", "Gold", "winRate", "unknown")));
        assertThat(UserRecommendationResponse.from(user, 2, true).winRate()).isNull();
        profile.updateGameStats(Map.of("R6", Map.of("connected", true, "tierLabel", "Gold")));
        assertThat(UserRecommendationResponse.from(user, 2, true).winRate()).isNull();
    }
}
