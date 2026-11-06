package com.gamerin.backend.domain.valorant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.game.service.GameAccountConflict;
import com.gamerin.backend.domain.game.service.GameStatsPersistenceService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.valorant.client.HenrikValorantClient;
import com.gamerin.backend.domain.valorant.dto.request.ValorantConnectRequest;
import com.gamerin.backend.domain.valorant.dto.response.ValorantConnectionResponse;
import com.gamerin.backend.domain.valorant.dto.response.ValorantSummaryResponse;
import com.gamerin.backend.domain.valorant.model.ValorantAccount;
import com.gamerin.backend.domain.valorant.model.ValorantMatchStats;
import com.gamerin.backend.domain.valorant.model.ValorantRank;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.gamerin.backend.domain.valorant.model.ValorantSummaryStats;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 발로란트 연동. Riot(LoL) 연동과 별개로 game_stats.VALORANT와 VALORANT 연결 버전을 쓴다. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ValorantService {

    private static final String GAME = GameType.VALORANT.name();
    private static final String PLATFORM = "PC";
    private static final String UNRATED = "Unrated";
    // HenrikDev Basic 키는 분당 30회라 사용자별 외부 재조회 간격을 둔다.
    static final Duration REFRESH_COOLDOWN = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final HenrikValorantClient henrikValorantClient;
    private final GameStatsPersistenceService gameStatsPersistenceService;

    public ValorantService(UserRepository userRepository, HenrikValorantClient henrikValorantClient,
            GameStatsPersistenceService gameStatsPersistenceService) {
        this.userRepository = userRepository;
        this.henrikValorantClient = henrikValorantClient;
        this.gameStatsPersistenceService = gameStatsPersistenceService;
    }

    public ValorantConnectionResponse connect(CustomUserPrincipal principal, ValorantConnectRequest request) {
        User user = getCurrentUser(principal);
        getCurrentProfile(user);
        String[] riotId = parseRiotId(request.riotId());
        ValorantRegion region = request.region();

        ValorantAccount account = henrikValorantClient.findAccount(riotId[0], riotId[1]);
        if (userRepository.existsConnectedValorantPuuidByOtherUser(user.getId(), account.puuid())) {
            throw GameAccountConflict.VALORANT.conflict();
        }

        ValorantSummaryStats summary = summarize(henrikValorantClient.getRank(region, account.puuid()),
                henrikValorantClient.getRecentCompetitiveMatches(region, account.puuid()));
        OffsetDateTime updatedAt = OffsetDateTime.now();
        try {
            gameStatsPersistenceService.updateConnection(user.getId(), current -> current.connectValorant(
                    account.riotId(), account.puuid(), region.name(), PLATFORM, summary, updatedAt));
        } catch (DataIntegrityViolationException failure) {
            // Catch outside the writer's transaction so commit-time conflicts have already rolled back.
            throw GameAccountConflict.VALORANT.translate(failure);
        }
        return new ValorantConnectionResponse(true, account.riotId(), region, PLATFORM);
    }

    @Transactional(readOnly = true)
    public ValorantSummaryResponse getMySummary(CustomUserPrincipal principal) {
        UserProfile profile = getCurrentProfile(getCurrentUser(principal));
        return toResponse(profile);
    }

    public ValorantSummaryResponse refreshMySummary(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        UserProfile profile = getCurrentProfile(user);
        String puuid = profile.getValorantPuuid();
        ValorantRegion region = parseRegion(profile.getValorantRegion());
        if (!profile.hasConnectedValorant() || puuid == null || region == null) {
            return disconnectedResponse();
        }

        OffsetDateTime lastUpdatedAt = profile.getValorantUpdatedAt();
        // shortcut: 쿨다운 검사가 행 잠금 밖이라 동시 요청은 함께 통과할 수 있다. 호출 한도가 부족해지면 잠금 안으로 옮긴다.
        if (lastUpdatedAt != null && lastUpdatedAt.plus(REFRESH_COOLDOWN).isAfter(OffsetDateTime.now())) {
            return toResponse(profile);
        }

        long connectionVersion = profile.getGameConnectionVersion(GAME);
        ValorantRank rank = henrikValorantClient.getRank(region, puuid);
        ValorantSummaryStats summary = summarize(rank, henrikValorantClient.getRecentCompetitiveMatches(region, puuid));
        OffsetDateTime updatedAt = OffsetDateTime.now();
        UserProfile current = gameStatsPersistenceService.updateSummary(
                user.getId(), GAME, connectionVersion,
                latest -> latest.hasConnectedValorant() && puuid.equals(latest.getValorantPuuid()),
                latest -> latest.updateValorantSummary(rank.riotId(), summary, updatedAt)
        );
        return toResponse(current);
    }

    public void disconnect(CustomUserPrincipal principal) {
        User user = getCurrentUser(principal);
        getCurrentProfile(user);
        gameStatsPersistenceService.updateConnection(user.getId(), UserProfile::disconnectValorant);
    }

    static ValorantSummaryStats summarize(ValorantRank rank, List<ValorantMatchStats> matches) {
        String tierLabel = rank.tierLabel() == null ? UNRATED : rank.tierLabel();
        Integer rr = UNRATED.equalsIgnoreCase(tierLabel) ? null : rank.rr();
        if (matches.isEmpty()) {
            return new ValorantSummaryStats(tierLabel, rank.peakTierLabel(), rr, null, null, null, null, 0);
        }

        int kills = 0, deaths = 0, score = 0, rounds = 0, shots = 0, headshots = 0, wins = 0;
        for (ValorantMatchStats match : matches) {
            kills += match.kills();
            deaths += match.deaths();
            score += match.score();
            rounds += match.rounds();
            headshots += match.headshots();
            shots += match.headshots() + match.bodyshots() + match.legshots();
            wins += match.won() ? 1 : 0;
        }
        return new ValorantSummaryStats(
                tierLabel,
                rank.peakTierLabel(),
                rr,
                // 데스가 0이면 킬 수를 그대로 K/D로 쓴다.
                BigDecimal.valueOf(kills).divide(BigDecimal.valueOf(Math.max(deaths, 1)), 2, RoundingMode.DOWN)
                        .doubleValue(),
                rounds == 0 ? null : Math.round((float) score / rounds),
                shots == 0 ? null : Math.round(headshots * 100f / shots),
                Math.round(wins * 100f / matches.size()),
                matches.size()
        );
    }

    private String[] parseRiotId(String value) {
        String[] parts = value == null ? new String[0] : value.strip().split("#", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot ID 형식은 '닉네임#태그'여야 합니다.");
        }
        return new String[] {parts[0].strip(), parts[1].strip()};
    }

    private ValorantRegion parseRegion(String value) {
        try {
            return value == null ? null : ValorantRegion.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ValorantSummaryResponse toResponse(UserProfile profile) {
        if (!profile.hasConnectedValorant()) {
            return disconnectedResponse();
        }
        ValorantSummaryStats summary = profile.getValorantSummary();
        return new ValorantSummaryResponse(
                GAME,
                true,
                profile.getValorantRiotId(),
                profile.getValorantRegion(),
                profile.getValorantPlatform(),
                summary.tierLabel(),
                summary.peakTierLabel(),
                summary.rr(),
                summary.kd(),
                summary.acs(),
                summary.headshotRate(),
                summary.winRate(),
                summary.matches(),
                profile.getValorantUpdatedAt()
        );
    }

    private ValorantSummaryResponse disconnectedResponse() {
        return new ValorantSummaryResponse(GAME, false, null, null, PLATFORM,
                null, null, null, null, null, null, null, null, null);
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return userRepository.findWithProfileById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private UserProfile getCurrentProfile(User user) {
        UserProfile profile = user.getProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User profile is not initialized.");
        }
        return profile;
    }
}
