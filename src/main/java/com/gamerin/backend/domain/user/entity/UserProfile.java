package com.gamerin.backend.domain.user.entity;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class UserProfile {

    private static final String PUBG_KEY = "PUBG";
    private static final String ACCOUNT_ID_KEY = "accountId";
    private static final String PLAYER_NAME_KEY = "playerName";
    private static final String CONNECTED_KEY = "connected";
    private static final String TIER_LABEL_KEY = "tierLabel";
    private static final String KDA_KEY = "kda";
    private static final String WIN_RATE_KEY = "winRate";
    private static final String GAMES_KEY = "games";

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

    public boolean hasConnectedPubg() {
        Object connected = getPubgStats().get(CONNECTED_KEY);
        return connected instanceof Boolean value && value;
    }

    public String getPubgAccountId() {
        Object accountId = getPubgStats().get(ACCOUNT_ID_KEY);
        return accountId instanceof String value && !value.isBlank() ? value : null;
    }

    public void connectPubg(String playerName, String accountId) {
        Map<String, Object> pubgStats = new HashMap<>(getPubgStats());
        pubgStats.put(ACCOUNT_ID_KEY, accountId);
        pubgStats.put(PLAYER_NAME_KEY, playerName);
        pubgStats.put(CONNECTED_KEY, true);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(PUBG_KEY, pubgStats);
        this.gameStats = nextGameStats;
    }

    public void updatePubgSummary(String tierLabel, double kda, int winRate, int games) {
        Map<String, Object> pubgStats = new HashMap<>(getPubgStats());
        pubgStats.put(CONNECTED_KEY, true);
        pubgStats.put(TIER_LABEL_KEY, tierLabel);
        pubgStats.put(KDA_KEY, kda);
        pubgStats.put(WIN_RATE_KEY, winRate);
        pubgStats.put(GAMES_KEY, games);

        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.put(PUBG_KEY, pubgStats);
        this.gameStats = nextGameStats;
    }

    public void disconnectPubg() {
        Map<String, Object> nextGameStats = new HashMap<>(getSafeGameStats());
        nextGameStats.remove(PUBG_KEY);
        this.gameStats = nextGameStats;
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

    private Map<String, Object> getSafeGameStats() {
        return Objects.requireNonNullElseGet(this.gameStats, HashMap::new);
    }
}  
