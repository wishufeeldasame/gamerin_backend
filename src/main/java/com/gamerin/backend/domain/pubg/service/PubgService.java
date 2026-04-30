package com.gamerin.backend.domain.pubg.service;

import java.util.UUID;

import com.gamerin.backend.domain.pubg.client.PubgApiClient;
import com.gamerin.backend.domain.pubg.dto.request.PubgConnectRequest;
import com.gamerin.backend.domain.pubg.dto.response.PubgConnectionResponse;
import com.gamerin.backend.domain.pubg.dto.response.PubgSummaryResponse;
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
    private static final String RANKED_MODE = "squad-tpp";
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
            return new PubgSummaryResponse(GAME_NAME, null, 0.0, 0, 0, false);
        }

        String accountId = profile.getPubgAccountId();
        if (accountId == null) {
            return new PubgSummaryResponse(GAME_NAME, null, 0.0, 0, 0, false);
        }

        String seasonId = pubgApiClient.findCurrentSeasonId();

        try {
            RankedStats rankedStats = pubgApiClient.getRankedStats(accountId, seasonId, RANKED_MODE);
            PubgSummaryResponse response = toRankedSummary(rankedStats);
            profile.updatePubgSummary(response.tierLabel(), response.kda(), response.winRate(), response.games());
            return response;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw e;
            }
        }

        NormalStats normalStats = pubgApiClient.getNormalStats(accountId, seasonId, NORMAL_MODE);
        PubgSummaryResponse response = toNormalSummary(normalStats);
        profile.updatePubgSummary(response.tierLabel(), response.kda(), response.winRate(), response.games());
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

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private PubgSummaryResponse toRankedSummary(RankedStats stats) {
        int games = stats.roundsPlayed();
        int wins = stats.wins();
        int winRate = games == 0 ? 0 : (int) Math.round((wins * 100.0) / games);

        return new PubgSummaryResponse(
                GAME_NAME,
                toTierLabel(stats.currentTier(), stats.currentSubTier()),
                round1(stats.kda()),
                winRate,
                games,
                true
        );
    }

    private PubgSummaryResponse toNormalSummary(NormalStats stats) {
        int games = stats.roundsPlayed();
        int wins = stats.wins();
        int winRate = games == 0 ? 0 : (int) Math.round((wins * 100.0) / games);

        return new PubgSummaryResponse(
                GAME_NAME,
                null,
                round1(stats.kda()),
                winRate,
                games,
                true
        );
    }
}
