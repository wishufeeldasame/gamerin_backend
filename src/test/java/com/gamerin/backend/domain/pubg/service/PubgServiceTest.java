package com.gamerin.backend.domain.pubg.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.pubg.client.PubgApiClient;
import com.gamerin.backend.domain.pubg.dto.response.PubgSummaryResponse;
import com.gamerin.backend.domain.pubg.model.NormalStats;
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
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No ranked stats found."));
        when(pubgApiClient.getNormalStats("account-1", "season-1", "squad"))
                .thenReturn(new NormalStats(1.26, 10, 2));

        PubgSummaryResponse response = pubgService.getMySummary(CustomUserPrincipal.from(user));

        assertThat(response.connected()).isTrue();
        assertThat(response.tierLabel()).isNull();
        assertThat(response.kda()).isEqualTo(1.26);
        assertThat(response.winRate()).isEqualTo(20);
        assertThat(response.games()).isEqualTo(10);
        verify(pubgApiClient).getNormalStats("account-1", "season-1", "squad");
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }
}
