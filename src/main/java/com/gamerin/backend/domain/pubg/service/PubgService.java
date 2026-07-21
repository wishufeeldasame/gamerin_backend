package com.gamerin.backend.domain.pubg.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.pubg.client.PubgApiClient;
import com.gamerin.backend.domain.pubg.dto.request.PubgConnectRequest;
import com.gamerin.backend.domain.pubg.dto.response.PubgConnectionResponse;
import com.gamerin.backend.domain.pubg.dto.response.PubgSummaryResponse;
import com.gamerin.backend.domain.pubg.exception.NoRankedRecordException;
import com.gamerin.backend.domain.pubg.model.NormalStats;
import com.gamerin.backend.domain.pubg.model.RankedStats;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class PubgService {

    private static final String GAME_NAME = "PUBG";
    private static final String RANKED_MODE = "squad";
    private static final String NORMAL_MODE = "squad";

    private final UserRepository userRepository;
    private final PubgApiClient pubgApiClient;

    public PubgService(UserRepository userRepository, PubgApiClient pubgApiClient) {
        this.userRepository = userRepository;
        this.pubgApiClient = pubgApiClient;
    }

    public PubgConnectionResponse connect(CustomUserPrincipal principal, PubgConnectRequest request) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);

        String playerName = request.playerName();

        validatePubgPlayerNameDuplicate(user.getId(), playerName);

        String accountId = pubgApiClient.findAccountId(playerName);

        profile.connectPubg(playerName, accountId);
        return new PubgConnectionResponse(true, playerName);
    }

    private void validatePubgPlayerNameDuplicate(UUID userId, String playerName) {
        boolean duplicated = userRepository.existsConnectedPubgPlayerNameByOtherUser(userId, playerName);

        if (duplicated) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "이미 다른 유저가 사용 중인 PUBG 닉네임입니다."
            );
        }
    }

    public PubgSummaryResponse getMySummary(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);

        if (!profile.hasConnectedPubg()) {
            return disconnectedResponse();
        }

        String accountId = profile.getPubgAccountId();
        if (accountId == null) {
            return disconnectedResponse();
        }
        String playerName = profile.getPubgPlayerName();

        String seasonId = pubgApiClient.findCurrentSeasonId();

        RankedStats rankedStats;
        try {
            rankedStats = pubgApiClient.getRankedStats(accountId, seasonId, RANKED_MODE);
        } catch (NoRankedRecordException e) {
            NormalStats normalStats = pubgApiClient.getNormalStats(accountId, seasonId, NORMAL_MODE);
            PubgSummaryResponse response = toNormalSummary(playerName, normalStats);
            profile.updatePubgSummary(
                    response.tierLabel(),
                    response.kd(),
                    response.winRate(),
                    response.matches(),
                    response.statsMode()
            );
            return response;
        }

        PubgSummaryResponse response = toRankedSummary(playerName, rankedStats);
        profile.updatePubgSummary(
                response.tierLabel(),
                response.kd(),
                response.winRate(),
                response.matches(),
                response.statsMode()
        );
        return response;
    }

    public void disconnect(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);
        profile.disconnectPubg();
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private UserProfile getCurrentProfile(User user) {
        UserProfile profile = user.getProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User profile is not initialized.");
        }
        return profile;
    }

    private String toTierLabel(String tier, String subTier) {
        if (tier == null || tier.isBlank()) {
            return null;
        }
        if (subTier == null || subTier.isBlank()) {
            return tier;
        }
        return tier + " " + subTier;
    }

    private Double truncate2(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.DOWN)
                .doubleValue();
    }

    private PubgSummaryResponse toRankedSummary(String playerName, RankedStats stats) {
        int matches = stats.roundsPlayed();

        return new PubgSummaryResponse(
                GAME_NAME,
                true,
                playerName,
                toTierLabel(stats.currentTier(), stats.currentSubTier()),
                truncate2(stats.kd()),
                calculateWinRate(stats.wins(), matches),
                matches,
                GameStatsMode.RANKED
        );
    }

    private PubgSummaryResponse toNormalSummary(String playerName, NormalStats stats) {
        Integer matches = stats.roundsPlayed();
        return new PubgSummaryResponse(
                GAME_NAME,
                true,
                playerName,
                null,
                truncate2(stats.kd()),
                calculateWinRate(stats.wins(), matches),
                matches,
                matches == null || matches <= 0 ? null : GameStatsMode.NORMAL
        );
    }

    private Integer calculateWinRate(Integer wins, Integer matches) {
        if (wins == null || matches == null || matches <= 0 || wins < 0 || wins > matches) {
            return null;
        }
        return (int) Math.round(wins * 100.0 / matches);
    }

    private PubgSummaryResponse disconnectedResponse() {
        return new PubgSummaryResponse(GAME_NAME, false, null, null, null, null, null, null);
    }
}
