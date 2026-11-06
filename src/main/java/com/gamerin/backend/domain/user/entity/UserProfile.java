package com.gamerin.backend.domain.user.entity;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.game.model.GameType;
import com.gamerin.backend.domain.valorant.model.ValorantSummaryStats;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_profiles")
// Profile edits must not write a stale game_stats value loaded before a game update.
@DynamicUpdate
public class UserProfile {

    private static final String PUBG_KEY = GameType.PUBG.name();
    private static final String R6_KEY = GameType.R6.name();
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String PLAYER_NAME_KEY = "playerName";
    private static final String PLAYER_NAME_NORMALIZED_KEY = "playerNameNormalized";
    private static final String PLATFORM_KEY = "platform";
    private static final String CONNECTED_KEY = "connected";
    private static final String TIER_LABEL_KEY = "tierLabel";
    private static final String KDA_KEY = "kda";
    private static final String KD_KEY = "kd";
    private static final String WIN_RATE_KEY = "winRate";
    private static final String GAMES_KEY = "games";
    private static final String MATCHES_KEY = "matches";
    private static final String STATS_MODE_KEY = "statsMode";
    private static final String UPDATED_AT_KEY = "updatedAt";

    private static final String RIOT_KEY = "RIOT";
    private static final String LOL_KEY = GameType.LOL.name();
    private static final String PUUID_KEY = "puuid";
    private static final String RIOT_ID_KEY = "riotId";

    private static final String VALORANT_KEY = GameType.VALORANT.name();
    private static final String REGION_KEY = "region";
    private static final String PEAK_TIER_LABEL_KEY = "peakTierLabel";
    private static final String RR_KEY = "rr";
    private static final String ACS_KEY = "acs";
    private static final String HEADSHOT_RATE_KEY = "headshotRate";

    @Id
    @Column
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId // User 엔티티의 ID를 공유
    @JoinColumn(name = "user_id")
    private User user;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(length = 100)
    private String location;

    @Column(name = "cover_image_url", columnDefinition = "TEXT")
    private String coverImageUrl;

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    private String profileImageUrl;

    @Column(columnDefinition = "TEXT")
    private String website;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "game_stats", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> gameStats = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "game_connection_versions", columnDefinition = "jsonb", nullable = false)
    private Map<String, Long> gameConnectionVersions = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "representative_game", length = 20)
    private GameType representativeGame;

    @Column(name = "verified_badge", nullable = false)
    private boolean verifiedBadge = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserProfile() {
    }

    // 새 프로필 생성을 위한 정적 팩토리 메서드
    public static UserProfile createDefault(User user) {
        UserProfile profile = new UserProfile();
        profile.user = user;
        profile.userId = user.getId();
        profile.gameStats = new HashMap<>(); // 초기 빈 값
        return profile;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // Getter 및 Setter
    public UUID getUserId() {
        return userId;
    }

    public User getUser() {
        return user;
    }

    public String getBio() {
        return bio;
    }

    public String getLocation() {
        return location;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public String getWebsite() {
        return website;
    }

    public Map<String, Object> getGameStats() {
        return gameStats;
    }

    public long getGameConnectionVersion(String gameKey) {
        return gameConnectionVersions.getOrDefault(gameKey, 0L);
    }

    public GameType getRepresentativeGame() {
        return representativeGame;
    }

    public boolean isVerifiedBadge() {
        return verifiedBadge;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    // 비즈니스 메서드
    public void updateBio(String bio) {
        this.bio = bio;
    }

    public void updateLocation(String location) {
        this.location = location;
    }

    public void updateCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public void updateProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public void updateWebsite(String website) {
        this.website = website;
    }

    public void updateGameStats(Map<String, Object> gameStats) {
        this.gameStats = gameStats;
    }

    // 탈퇴 시 공개 프로필과 게임 연동(유니크 인덱스 대상)을 비운다
    public void clearForWithdrawal() {
        this.bio = null;
        this.location = null;
        this.website = null;
        this.profileImageUrl = null;
        this.coverImageUrl = null;
        this.gameStats = new HashMap<>();
        this.representativeGame = null;
        this.verifiedBadge = false;
    }

    public boolean hasConnectedGame(GameType game) {
        return switch (game) {
            case PUBG -> hasConnectedPubg();
            case R6 -> hasConnectedR6();
            case LOL -> hasConnectedRiot();
            // 발로란트(#178)는 Riot(LoL) 연동과 별개로 VALORANT 키에 연동 상태를 저장한다.
            case VALORANT -> hasConnectedValorant();
            default -> false;
        };
    }

    /** 호출자가 {@link #hasConnectedGame}으로 연동 여부를 먼저 검증한다. null이면 표시하지 않는다. */
    public void changeRepresentativeGame(GameType game) {
        this.representativeGame = game;
    }

    /** 대표 게임의 마지막 저장 티어. 대표 없음·연동 해제·티어 없음·UNRANKED·UNRATED면 null이다. */
    public String getRepresentativeTierLabel() {
        if (representativeGame == null || !hasConnectedGame(representativeGame)) {
            return null;
        }
        String tierLabel = getStringValue(getGameStatsFor(representativeGame.name()), TIER_LABEL_KEY);
        // LoL은 UNRANKED, 발로란트(HenrikDev)는 Unrated로 배치 전 상태를 표시한다.
        return tierLabel == null || "UNRANKED".equalsIgnoreCase(tierLabel) || "UNRATED".equalsIgnoreCase(tierLabel)
                ? null : tierLabel;
    }

    private void clearRepresentativeGameIf(GameType game) {
        if (representativeGame == game) {
            representativeGame = null;
        }
    }

    public boolean hasConnectedPubg() {
        Object connected = getPubgStats().get(CONNECTED_KEY);
        return connected instanceof Boolean value && value;
    }

    public String getPubgAccountId() {
        Object accountId = getPubgStats().get(ACCOUNT_ID_KEY);
        return accountId instanceof String value && !value.isBlank() ? value : null;
    }

    public String getPubgPlayerName() {
        return getStringValue(getPubgStats(), PLAYER_NAME_KEY);
    }

    public void connectPubg(String playerName, String accountId) {
        String previousAccountId = getPubgAccountId();
        // Cached statistics belong to the account, not its display name.
        Map<String, Object> pubgStats = previousAccountId != null && previousAccountId.equals(accountId)
                ? new HashMap<>(getPubgStats()) : new HashMap<>();
        pubgStats.put(ACCOUNT_ID_KEY, accountId);
        pubgStats.put(PLAYER_NAME_KEY, playerName);
        pubgStats.put(CONNECTED_KEY, true);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(PUBG_KEY, pubgStats);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(PUBG_KEY);
    }

    public void updatePubgSummary(
            String tierLabel,
            Double kd,
            Integer winRate,
            Integer matches,
            GameStatsMode statsMode
    ) {
        Map<String, Object> pubgStats = new HashMap<>(getPubgStats());
        pubgStats.put(CONNECTED_KEY, true);
        putNullable(pubgStats, TIER_LABEL_KEY, tierLabel);
        putNullable(pubgStats, KD_KEY, kd);
        putNullable(pubgStats, WIN_RATE_KEY, winRate);
        putNullable(pubgStats, MATCHES_KEY, matches);
        putNullable(pubgStats, STATS_MODE_KEY, statsMode == null ? null : statsMode.name());

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(PUBG_KEY, pubgStats);
        this.gameStats = nextGameStats;
    }

    public void disconnectPubg() {
        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.remove(PUBG_KEY);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(PUBG_KEY);
        clearRepresentativeGameIf(GameType.PUBG);
    }

    public boolean hasConnectedR6() {
        Object connected = getR6Stats().get(CONNECTED_KEY);
        return connected instanceof Boolean value && value;
    }

    public String getR6PlayerName() {
        return getStringValue(getR6Stats(), PLAYER_NAME_KEY);
    }

    public String getR6AccountId() {
        return getStringValue(getR6Stats(), ACCOUNT_ID_KEY);
    }

    public String getR6ConnectedPlatform() {
        return getStringValue(getR6Stats(), PLATFORM_KEY);
    }

    public String getR6TierLabel() {
        return getStringValue(getR6Stats(), TIER_LABEL_KEY);
    }

    public Double getR6Kd() {
        return getDoubleValue(getR6Stats(), KD_KEY);
    }

    public Integer getR6WinRate() {
        return getIntegerValue(getR6Stats(), WIN_RATE_KEY);
    }

    public Integer getR6Matches() {
        return getIntegerValue(getR6Stats(), MATCHES_KEY);
    }

    public OffsetDateTime getR6UpdatedAt() {
        return parseOffsetDateTime(getStringValue(getR6Stats(), UPDATED_AT_KEY));
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public GameStatsMode getR6StatsMode() {
        return getGameStatsModeValue(getR6Stats(), STATS_MODE_KEY);
    }

    public void connectR6(
            String playerName,
            String playerNameNormalized,
            String platform,
            String accountId,
            String tierLabel,
            Double kd,
            Integer winRate,
            Integer matches,
            GameStatsMode statsMode,
            OffsetDateTime updatedAt
    ) {
        Map<String, Object> r6Stats = new HashMap<>(getR6Stats());
        r6Stats.put(CONNECTED_KEY, true);
        r6Stats.put(PLAYER_NAME_KEY, playerName);
        r6Stats.put(PLAYER_NAME_NORMALIZED_KEY, playerNameNormalized);
        r6Stats.put(PLATFORM_KEY, platform);
        putNullable(r6Stats, ACCOUNT_ID_KEY, accountId);
        putR6SummaryValues(r6Stats, tierLabel, kd, winRate, matches, statsMode, updatedAt);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(R6_KEY, r6Stats);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(R6_KEY);
    }

    public void updateR6Summary(
            String tierLabel,
            Double kd,
            Integer winRate,
            Integer matches,
            GameStatsMode statsMode,
            OffsetDateTime updatedAt
    ) {
        Map<String, Object> r6Stats = new HashMap<>(getR6Stats());
        r6Stats.put(CONNECTED_KEY, true);
        putR6SummaryValues(r6Stats, tierLabel, kd, winRate, matches, statsMode, updatedAt);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(R6_KEY, r6Stats);
        this.gameStats = nextGameStats;
    }

    public void disconnectR6() {
        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.remove(R6_KEY);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(R6_KEY);
        clearRepresentativeGameIf(GameType.R6);
    }

    public boolean hasConnectedRiot() {
        Object connected = getRiotStats().get(CONNECTED_KEY);
        return connected instanceof Boolean value && value;
    }

    public String getRiotPuuid() {
        Object puuid = getRiotStats().get(PUUID_KEY);
        return puuid instanceof String value && !value.isBlank() ? value : null;
    }

    public String getRiotId() {
        Object riotId = getRiotStats().get(RIOT_ID_KEY);
        return riotId instanceof String value && !value.isBlank() ? value : null;
    }

    public void connectRiot(String riotId, String puuid) {
        String previousPuuid = getRiotPuuid();
        Map<String, Object> riotStats = new HashMap<>(getRiotStats());
        riotStats.put(PUUID_KEY, puuid);
        riotStats.put(RIOT_ID_KEY, riotId);
        riotStats.put(CONNECTED_KEY, true);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        // An absent owner cannot establish that a legacy cache belongs to this account.
        if (previousPuuid == null || !previousPuuid.equals(puuid)) {
            nextGameStats.remove(LOL_KEY);
        }
        nextGameStats.put(RIOT_KEY, riotStats);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(RIOT_KEY);
    }

    public void updateLolSummary(String tierLabel, double kda, int winRate, int games) {
        Map<String, Object> lolStats = new HashMap<>(getGameStatsFor(LOL_KEY));
        lolStats.put(CONNECTED_KEY, true);
        lolStats.put(TIER_LABEL_KEY, tierLabel);
        lolStats.put(KDA_KEY, kda);
        lolStats.put(WIN_RATE_KEY, winRate);
        lolStats.put(GAMES_KEY, games);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(LOL_KEY, lolStats);
        this.gameStats = nextGameStats;
    }

    public void disconnectRiot() {
        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.remove(RIOT_KEY);
        nextGameStats.remove(LOL_KEY);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(RIOT_KEY);
        clearRepresentativeGameIf(GameType.LOL);
    }

    public boolean hasConnectedValorant() {
        return Boolean.TRUE.equals(getGameStatsFor(VALORANT_KEY).get(CONNECTED_KEY));
    }

    public String getValorantRiotId() {
        return getStringValue(getGameStatsFor(VALORANT_KEY), RIOT_ID_KEY);
    }

    /** HenrikDev 기준 PUUID. Riot 공식 API의 RIOT.puuid와 같은 값이라고 가정하지 않는다. */
    public String getValorantPuuid() {
        return getStringValue(getGameStatsFor(VALORANT_KEY), PUUID_KEY);
    }

    public String getValorantRegion() {
        return getStringValue(getGameStatsFor(VALORANT_KEY), REGION_KEY);
    }

    public String getValorantPlatform() {
        return getStringValue(getGameStatsFor(VALORANT_KEY), PLATFORM_KEY);
    }

    public ValorantSummaryStats getValorantSummary() {
        Map<String, Object> stats = getGameStatsFor(VALORANT_KEY);
        return new ValorantSummaryStats(
                getStringValue(stats, TIER_LABEL_KEY),
                getStringValue(stats, PEAK_TIER_LABEL_KEY),
                getIntegerValue(stats, RR_KEY),
                getDoubleValue(stats, KD_KEY),
                getIntegerValue(stats, ACS_KEY),
                getIntegerValue(stats, HEADSHOT_RATE_KEY),
                getIntegerValue(stats, WIN_RATE_KEY),
                getIntegerValue(stats, MATCHES_KEY)
        );
    }

    public OffsetDateTime getValorantUpdatedAt() {
        return parseOffsetDateTime(getStringValue(getGameStatsFor(VALORANT_KEY), UPDATED_AT_KEY));
    }

    public void connectValorant(
            String riotId,
            String puuid,
            String region,
            String platform,
            ValorantSummaryStats summary,
            OffsetDateTime updatedAt
    ) {
        // A new connection never inherits statistics cached for a previous account.
        Map<String, Object> valorantStats = new HashMap<>();
        valorantStats.put(CONNECTED_KEY, true);
        valorantStats.put(RIOT_ID_KEY, riotId);
        valorantStats.put(PUUID_KEY, puuid);
        valorantStats.put(REGION_KEY, region);
        valorantStats.put(PLATFORM_KEY, platform);
        putValorantSummaryValues(valorantStats, summary, updatedAt);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(VALORANT_KEY, valorantStats);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(VALORANT_KEY);
    }

    public void updateValorantSummary(ValorantSummaryStats summary, OffsetDateTime updatedAt) {
        Map<String, Object> valorantStats = new HashMap<>(getGameStatsFor(VALORANT_KEY));
        valorantStats.put(CONNECTED_KEY, true);
        putValorantSummaryValues(valorantStats, summary, updatedAt);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(VALORANT_KEY, valorantStats);
        this.gameStats = nextGameStats;
    }

    public void disconnectValorant() {
        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.remove(VALORANT_KEY);
        this.gameStats = nextGameStats;
        advanceGameConnectionVersion(VALORANT_KEY);
        clearRepresentativeGameIf(GameType.VALORANT);
    }

    private void putValorantSummaryValues(
            Map<String, Object> valorantStats,
            ValorantSummaryStats summary,
            OffsetDateTime updatedAt
    ) {
        putNullable(valorantStats, TIER_LABEL_KEY, summary.tierLabel());
        putNullable(valorantStats, PEAK_TIER_LABEL_KEY, summary.peakTierLabel());
        putNullable(valorantStats, RR_KEY, summary.rr());
        putNullable(valorantStats, KD_KEY, summary.kd());
        putNullable(valorantStats, ACS_KEY, summary.acs());
        putNullable(valorantStats, HEADSHOT_RATE_KEY, summary.headshotRate());
        putNullable(valorantStats, WIN_RATE_KEY, summary.winRate());
        putNullable(valorantStats, MATCHES_KEY, summary.matches());
        putNullable(valorantStats, UPDATED_AT_KEY, updatedAt == null ? null : updatedAt.toString());
    }

    private void advanceGameConnectionVersion(String gameKey) {
        // Retain the counter after disconnect so reconnecting the same account invalidates old requests.
        Map<String, Long> nextVersions = new HashMap<>(gameConnectionVersions);
        nextVersions.put(gameKey, Math.incrementExact(getGameConnectionVersion(gameKey)));
        this.gameConnectionVersions = nextVersions;
    }

    private Map<String, Object> getRiotStats() {
        return getGameStatsFor(RIOT_KEY);
    }

    private Map<String, Object> getR6Stats() {
        return getGameStatsFor(R6_KEY);
    }

    private Map<String, Object> getGameStatsFor(String key) {
        Object stats = getSafeGameStats().get(key);
        if (stats instanceof Map<?, ?> map) {
            Map<String, Object> casted = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String k) {
                    casted.put(k, entry.getValue());
                }
            }
            return casted;
        }
        return new HashMap<>();
    }

    private Map<String, Object> getPubgStats() {
        Object pubgStats = getSafeGameStats().get(PUBG_KEY);
        if (pubgStats instanceof Map<?, ?> map) {
            Map<String, Object> casted = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    casted.put(key, entry.getValue());
                }
            }
            return casted;
        }
        return new HashMap<>();
    }

    private void putR6SummaryValues(
            Map<String, Object> r6Stats,
            String tierLabel,
            Double kd,
            Integer winRate,
            Integer matches,
            GameStatsMode statsMode,
            OffsetDateTime updatedAt
    ) {
        putNullable(r6Stats, TIER_LABEL_KEY, tierLabel);
        putNullable(r6Stats, KD_KEY, kd);
        putNullable(r6Stats, WIN_RATE_KEY, winRate);
        putNullable(r6Stats, MATCHES_KEY, matches);
        putNullable(r6Stats, STATS_MODE_KEY, statsMode == null ? null : statsMode.name());
        putNullable(r6Stats, UPDATED_AT_KEY, updatedAt == null ? null : updatedAt.toString());
    }

    private void putNullable(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            target.remove(key);
            return;
        }
        target.put(key, value);
    }

    private String getStringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private Double getDoubleValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Integer getIntegerValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private GameStatsMode getGameStatsModeValue(Map<String, Object> source, String key) {
        String value = getStringValue(source, key);
        if (value == null) {
            return null;
        }
        try {
            return GameStatsMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> getSafeGameStats() {
        return Objects.requireNonNullElseGet(this.gameStats, HashMap::new);
    }
}
