package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceGameStatsTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private MediaUploadSecurityService mediaUploadSecurityService;

    @Mock
    private LightweightSecurityScanService lightweightSecurityScanService;

    @Mock
    private MembershipService membershipService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                followRepository,
                postRepository,
                postMediaRepository,
                mediaStorageService,
                mediaUploadSecurityService,
                lightweightSecurityScanService,
                membershipService
        );
    }

    @Test
    void publicProfileKeepsCommonPubgAndR6FieldsAndRemovesAccountIdentifiers() {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        Map<String, Object> pubg = new HashMap<>();
        pubg.put("connected", true);
        pubg.put("playerName", "pubgPlayer");
        pubg.put("accountId", "pubg-account");
        pubg.put("tierLabel", "Gold III");
        pubg.put("kd", 1.42);
        pubg.put("winRate", 12);
        pubg.put("matches", 42);
        pubg.put("statsMode", "RANKED");

        Map<String, Object> r6 = new HashMap<>();
        r6.put("connected", true);
        r6.put("playerName", "R6Player");
        r6.put("platform", "PC");
        r6.put("tierLabel", "Gold");
        r6.put("kd", 1.18);
        r6.put("winRate", 48);
        r6.put("matches", 25);
        r6.put("statsMode", "NORMAL");
        r6.put("accountId", "r6-account");
        r6.put("playerNameNormalized", "r6player");
        r6.put("updatedAt", "2026-10-26T00:00:00Z");

        user.getProfile().updateGameStats(new HashMap<>(Map.of("PUBG", pubg, "R6", r6)));

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(user));

        var response = userService.getProfile(null, "target");

        Map<String, Object> publicPubg = nestedMap(response.gameStats(), "PUBG");
        Map<String, Object> publicR6 = nestedMap(response.gameStats(), "R6");
        assertThat(publicPubg)
                .containsEntry("connected", true)
                .containsEntry("playerName", "pubgPlayer")
                .containsEntry("tierLabel", "Gold III")
                .containsEntry("kd", 1.42)
                .containsEntry("winRate", 12)
                .containsEntry("matches", 42)
                .containsEntry("statsMode", "RANKED")
                .doesNotContainKey("accountId");
        assertThat(publicR6)
                .containsEntry("connected", true)
                .containsEntry("playerName", "R6Player")
                .containsEntry("platform", "PC")
                .containsEntry("tierLabel", "Gold")
                .containsEntry("kd", 1.18)
                .containsEntry("winRate", 48)
                .containsEntry("matches", 25)
                .containsEntry("statsMode", "NORMAL")
                .containsEntry("updatedAt", "2026-10-26T00:00:00Z")
                .doesNotContainKeys("accountId", "playerNameNormalized");

        assertThat(nestedMap(user.getProfile().getGameStats(), "PUBG")).containsKey("accountId");
        assertThat(nestedMap(user.getProfile().getGameStats(), "R6")).containsKey("accountId");
    }

    @Test
    void publicProfileKeepsLolSummaryAndRemovesRiotIdentifiersAndUnknownEntries() {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        UserProfile profile = user.getProfile();
        profile.connectRiot("player#KR1", "riot-puuid");
        profile.updateLolSummary("Gold I", 3.5, 60, 20);
        Map<String, Object> gameStats = new HashMap<>(profile.getGameStats());
        gameStats.put("LEGACY", Map.of("accountId", "legacy-account"));
        gameStats.put("note", "raw");
        profile.updateGameStats(gameStats);

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(user));

        var response = userService.getProfile(null, "target");

        assertThat(response.gameStats()).containsOnlyKeys("RIOT", "LOL");
        assertThat(nestedMap(response.gameStats(), "RIOT"))
                .containsOnlyKeys("connected")
                .containsEntry("connected", true);
        assertThat(nestedMap(response.gameStats(), "LOL"))
                .containsEntry("connected", true)
                .containsEntry("tierLabel", "Gold I")
                .containsEntry("kda", 3.5)
                .containsEntry("winRate", 60)
                .containsEntry("games", 20);
    }

    @Test
    void ownProfileKeepsAccountIdentifiersExceptR6AccountId() {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        UserProfile profile = user.getProfile();
        profile.connectRiot("player#KR1", "riot-puuid");
        profile.connectPubg("pubgPlayer", "pubg-account");
        profile.connectR6("R6Player", "r6player", "PC", "r6-account", null, null, null, null, null, null);

        when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));

        var response = userService.getMyProfile(user.getId());

        assertThat(nestedMap(response.gameStats(), "RIOT"))
                .containsEntry("riotId", "player#KR1")
                .containsEntry("puuid", "riot-puuid");
        assertThat(nestedMap(response.gameStats(), "PUBG")).containsEntry("accountId", "pubg-account");
        assertThat(nestedMap(response.gameStats(), "R6"))
                .containsEntry("playerNameNormalized", "r6player")
                .doesNotContainKey("accountId");
    }

    @Test
    void profileResponsePreservesNullGameStats() {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        user.getProfile().updateGameStats(null);

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(user));

        var response = userService.getProfile(null, "target");

        assertThat(response.gameStats()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicAndOwnProfilesDoNotExposePreviousAccountsSummaryAfterReplacement(boolean ownProfile) {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        UserProfile profile = user.getProfile();
        profile.connectRiot("original#KR1", "original-puuid");
        profile.updateLolSummary("Gold I", 3.5, 60, 20);
        profile.connectPubg("original", "original-account");
        profile.updatePubgSummary("Gold III", 1.42, 12, 42, GameStatsMode.RANKED);
        assertThat(profile.getGameStats()).containsKey("LOL");
        assertThat(nestedMap(profile.getGameStats(), "PUBG")).containsEntry("matches", 42);

        profile.connectRiot("replacement#KR1", "replacement-puuid");
        profile.connectPubg("replacement", "replacement-account");
        if (ownProfile) {
            when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));
        } else {
            when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(user));
        }

        var response = ownProfile ? userService.getMyProfile(user.getId()) : userService.getProfile(null, "target");

        assertThat(response.gameStats()).doesNotContainKey("LOL");
        assertThat(nestedMap(response.gameStats(), "RIOT")).containsEntry("connected", true);
        if (ownProfile) {
            assertThat(nestedMap(response.gameStats(), "RIOT")).containsEntry("riotId", "replacement#KR1");
        } else {
            assertThat(nestedMap(response.gameStats(), "RIOT")).doesNotContainKeys("riotId", "puuid");
        }
        assertThat(nestedMap(response.gameStats(), "PUBG"))
                .containsEntry("connected", true)
                .containsEntry("playerName", "replacement")
                .doesNotContainKeys("tierLabel", "kd", "winRate", "matches", "statsMode");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }
}
