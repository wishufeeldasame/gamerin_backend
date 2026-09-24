package com.gamerin.backend.domain.riot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.SQLException;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.game.service.GameStatsPersistenceService;
import com.gamerin.backend.domain.riot.client.RiotApiClient;
import com.gamerin.backend.domain.riot.dto.external.RiotAccountResponse;
import com.gamerin.backend.domain.riot.dto.request.RiotConnectRequest;
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
    private User user;

    @BeforeEach
    void setUp() {
        riotService = new RiotService(userRepository, riotApiClient, persistenceService);
        user = User.createLocal("riot@example.com", "riot", "Riot", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setProfile(UserProfile.createDefault(user));
        user.getProfile().connectRiot("player#KR1", "puuid");
        principal = CustomUserPrincipal.from(user);
        when(userRepository.findWithProfileById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void connectUsesExactPuuidAndPreservesExistingResponse() {
        when(riotApiClient.findAccount("newPlayer", "KR1"))
                .thenReturn(new RiotAccountResponse("CaseSensitivePuuid", "newPlayer", "KR1"));
        doAnswer(call -> {
            Consumer<UserProfile> update = call.getArgument(1);
            update.accept(user.getProfile());
            return null;
        }).when(persistenceService).updateConnection(eq(user.getId()), any());

        var response = riotService.connect(principal, new RiotConnectRequest("newPlayer#KR1"));

        assertThat(response.connected()).isTrue();
        assertThat(response.riotId()).isEqualTo("newPlayer#KR1");
        assertThat(user.getProfile().getRiotPuuid()).isEqualTo("CaseSensitivePuuid");
        verify(userRepository).existsConnectedRiotPuuidByOtherUser(user.getId(), "CaseSensitivePuuid");
    }

    @Test
    void connectRejectsAccountAlreadyLinkedByAnotherUserBeforeWriting() {
        when(riotApiClient.findAccount("player", "KR1"))
                .thenReturn(new RiotAccountResponse("puuid", "player", "KR1"));
        when(userRepository.existsConnectedRiotPuuidByOtherUser(user.getId(), "puuid")).thenReturn(true);

        assertThatThrownBy(() -> riotService.connect(principal, new RiotConnectRequest("player#KR1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("이미 다른 유저가 연동한 Riot 계정입니다.");
                });
        verifyNoInteractions(persistenceService);
    }

    @Test
    void connectExternalFailurePreservesStatusAndDoesNotWrite() {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limited");
        when(riotApiClient.findAccount("player", "KR1")).thenThrow(failure);
        assertThatThrownBy(() -> riotService.connect(principal, new RiotConnectRequest("player#KR1"))).isSameAs(failure);
        verifyNoInteractions(persistenceService);
    }

    @ParameterizedTest
    @CsvSource({
            "23505, unrelated_unique_index",
            "23505, uq_user_profiles_connected_r6_account",
            "23503, uq_user_profiles_connected_riot_puuid",
            "23505, ''"
    })
    void connectDoesNotMisclassifyUnrelatedIntegrityFailures(String sqlState, String constraint) {
        when(riotApiClient.findAccount("player", "KR1"))
                .thenReturn(new RiotAccountResponse("puuid", "player", "KR1"));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("write failure",
                new ConstraintViolationException("constraint failure", new SQLException("fixture", sqlState), constraint));
        doThrow(failure).when(persistenceService).updateConnection(eq(user.getId()), any());

        assertThatThrownBy(() -> riotService.connect(principal, new RiotConnectRequest("player#KR1"))).isSameAs(failure);
    }

    @Test
    void connectIntegrityFailureWithoutConstraintDetailsRemainsServerError() {
        when(riotApiClient.findAccount("player", "KR1"))
                .thenReturn(new RiotAccountResponse("puuid", "player", "KR1"));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("write failure");
        doThrow(failure).when(persistenceService).updateConnection(eq(user.getId()), any());
        assertThatThrownBy(() -> riotService.connect(principal, new RiotConnectRequest("player#KR1"))).isSameAs(failure);
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
