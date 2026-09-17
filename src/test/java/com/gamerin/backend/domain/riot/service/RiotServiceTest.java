package com.gamerin.backend.domain.riot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.game.service.GameStatsPersistenceService;
import com.gamerin.backend.domain.riot.client.RiotApiClient;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class RiotServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RiotApiClient riotApiClient;
    @Mock private GameStatsPersistenceService persistenceService;

    private RiotService riotService;
    private CustomUserPrincipal principal;

    @BeforeEach
    void setUp() {
        riotService = new RiotService(userRepository, riotApiClient, persistenceService);
        User user = User.createLocal("riot@example.com", "riot", "Riot", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setProfile(UserProfile.createDefault(user));
        user.getProfile().connectRiot("player#KR1", "puuid");
        principal = CustomUserPrincipal.from(user);
        when(userRepository.findWithProfileById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void summaryPersistenceFailurePropagatesToExistingServerErrorHandler() {
        when(riotApiClient.findLeagueEntriesByPuuid("puuid")).thenReturn(List.of());
        when(riotApiClient.findRecentMatchIds("puuid", 5)).thenReturn(List.of());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("test write failed");
        when(persistenceService.updateSummary(eq(principal.getUserId()), eq("RIOT"), anyLong(), any(), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> riotService.getLolSummary(principal)).isSameAs(failure);
    }

    @Test
    void externalApiStatusAndMessageRemainUnchanged() {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limited");
        when(riotApiClient.findLeagueEntriesByPuuid("puuid")).thenThrow(failure);

        assertThatThrownBy(() -> riotService.getLolSummary(principal)).isSameAs(failure);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void unexpectedExternalApiFailureRetainsBadGatewayResponse() {
        IllegalStateException failure = new IllegalStateException("test API failure");
        when(riotApiClient.findLeagueEntriesByPuuid("puuid")).thenThrow(failure);

        assertThatThrownBy(() -> riotService.getLolSummary(principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getReason()).isEqualTo("LoL 전적정보를 가져오는 데 실패했습니다.");
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        verifyNoInteractions(persistenceService);
    }
}
