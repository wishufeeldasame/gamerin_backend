package com.gamerin.backend.domain.r6.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Locale;

import com.gamerin.backend.domain.r6.client.R6StatsClient;
import com.gamerin.backend.domain.r6.dto.request.R6ConnectRequest;
import com.gamerin.backend.domain.r6.dto.response.R6ConnectionResponse;
import com.gamerin.backend.domain.r6.dto.response.R6SummaryResponse;
import com.gamerin.backend.domain.r6.model.R6Profile;
import com.gamerin.backend.domain.r6.model.R6ProfileRef;
import com.gamerin.backend.domain.r6.model.R6SummaryStats;
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
public class R6Service {

    private static final String GAME = "R6";
    private static final String PLATFORM = "PC";
    private static final int MAX_PLAYER_NAME_LENGTH = 100;

    private final UserRepository userRepository;
    private final R6StatsClient r6StatsClient;

    public R6Service(UserRepository userRepository, R6StatsClient r6StatsClient) {
        this.userRepository = userRepository;
        this.r6StatsClient = r6StatsClient;
    }

    public R6ConnectionResponse connect(CustomUserPrincipal principal, R6ConnectRequest request) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);
        String playerName = normalizeInputPlayerName(request.playerName());

        R6Profile r6Profile = r6StatsClient.findProfile(playerName);
        validateProfileIdentifier(r6Profile);

        R6SummaryStats summary = normalizeSummaryMetrics(r6Profile.summary());
        OffsetDateTime updatedAt = OffsetDateTime.now();
        profile.connectR6(
                playerName,
                normalizePlayerName(playerName),
                PLATFORM,
                trimToNull(r6Profile.accountId()),
                summary == null ? null : summary.tierLabel(),
                summary == null ? null : summary.kd(),
                summary == null ? null : summary.winRate(),
                summary == null ? null : summary.matches(),
                updatedAt
        );

        return new R6ConnectionResponse(true, playerName, PLATFORM);
    }

    @Transactional(readOnly = true)
    public R6SummaryResponse getMySummary(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);

        if (!profile.hasConnectedR6()) {
            return disconnectedResponse();
        }

        if (trimToNull(profile.getR6AccountId()) == null) {
            return disconnectedResponse();
        }

        return toSummaryResponse(profile);
    }

    public R6SummaryResponse refreshMySummary(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);

        if (!profile.hasConnectedR6()) {
            return disconnectedResponse();
        }

        R6ProfileRef profileRef = new R6ProfileRef(
                profile.getR6PlayerName(),
                profile.getR6AccountId()
        );
        if (trimToNull(profileRef.accountId()) == null) {
            return disconnectedResponse();
        }

        R6SummaryStats summary = normalizeSummaryMetrics(r6StatsClient.getSummary(profileRef));
        OffsetDateTime updatedAt = OffsetDateTime.now();
        profile.updateR6Summary(
                summary == null ? null : summary.tierLabel(),
                summary == null ? null : summary.kd(),
                summary == null ? null : summary.winRate(),
                summary == null ? null : summary.matches(),
                updatedAt
        );

        return toSummaryResponse(profile);
    }

    public void disconnect(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);
        profile.disconnectR6();
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

    private String normalizeInputPlayerName(String playerName) {
        String trimmed = trimToNull(playerName);
        if (trimmed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "R6 playerName is required.");
        }
        if (trimmed.length() > MAX_PLAYER_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "R6 playerName must be 100 characters or fewer.");
        }
        return trimmed;
    }

    private String normalizePlayerName(String playerName) {
        return playerName.strip().toLowerCase(Locale.ROOT);
    }

    private void validateProfileIdentifier(R6Profile profile) {
        if (profile == null
                || trimToNull(profile.accountId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected R6 stats API response.");
        }
    }

    private R6SummaryResponse disconnectedResponse() {
        return new R6SummaryResponse(GAME, false, null, PLATFORM, null, null, null, null, null);
    }

    private R6SummaryResponse toSummaryResponse(UserProfile profile) {
        return new R6SummaryResponse(
                GAME,
                profile.hasConnectedR6(),
                profile.getR6PlayerName(),
                PLATFORM,
                profile.getR6TierLabel(),
                profile.getR6Kd(),
                profile.getR6WinRate(),
                profile.getR6Matches(),
                profile.getR6UpdatedAt()
        );
    }

    private R6SummaryStats normalizeSummaryMetrics(R6SummaryStats summary) {
        if (summary == null) {
            return null;
        }
        return new R6SummaryStats(
                summary.tierLabel(),
                truncate2(summary.kd()),
                roundWinRate(summary.winRate()),
                summary.matches()
        );
    }

    private Double truncate2(Double value) {
        if (value == null) {
            return null;
        }
        validateFiniteMetric(value);
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.DOWN)
                .doubleValue();
    }

    private Double roundWinRate(Double value) {
        if (value == null) {
            return null;
        }
        validateFiniteMetric(value);
        return (double) Math.round(value);
    }

    private void validateFiniteMetric(Double value) {
        if (!Double.isFinite(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unexpected R6 stats API response.");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
