package com.gamerin.backend.domain.user.entity;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
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

    @Id
    @Column
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId // User 엔티티의 ID를 공유
    @JoinColumn(name = "user_id")
    private User user;
    
    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    private String profileImageUrl;

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

    public String getProfileImageUrl() {
        return profileImageUrl;
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

    public void updateProfileImage(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public void updateGameStats(Map<String, Object> gameStats) {
        this.gameStats = gameStats;
    }
}  
