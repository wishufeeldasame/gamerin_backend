package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
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
                lightweightSecurityScanService
        );
    }

    @Test
    void profileResponseKeepsCommonPubgAndR6FieldsAndRemovesR6InternalIdentifier() {
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
                .containsEntry("accountId", "pubg-account");
        assertThat(publicR6)
                .containsEntry("connected", true)
                .containsEntry("playerName", "R6Player")
                .containsEntry("platform", "PC")
                .containsEntry("tierLabel", "Gold")
                .containsEntry("kd", 1.18)
                .containsEntry("winRate", 48)
                .containsEntry("matches", 25)
                .containsEntry("statsMode", "NORMAL")
                .doesNotContainKey("accountId");

        assertThat(nestedMap(user.getProfile().getGameStats(), "PUBG")).containsKey("accountId");
        assertThat(nestedMap(user.getProfile().getGameStats(), "R6")).containsKey("accountId");
    }

    @Test
    void profileResponsePreservesNullGameStats() {
        User user = savedUser(UUID.randomUUID(), "target", "Target");
        user.getProfile().updateGameStats(null);

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(user));

        var response = userService.getProfile(null, "target");

        assertThat(response.gameStats()).isNull();
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
