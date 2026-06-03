package com.gamerin.backend.domain.mentoring.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.gamerin.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

@Entity
@Table(name = "mentor_profiles")
public class MentorProfile implements Persistable<UUID> {
    
    @Id
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MentorStatus status = MentorStatus.ACTIVE;

    private String about;

    @Column(precision = 3, scale = 2)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    private int reviewCount = 0;

    private int menteeCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 여기 부터 
    @Transient
    private boolean isNew = true; // 새로운 엔티티임을 표시

    @Override
    public UUID getId() {
        return userId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }  

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false; // 저장된 후나 DB에서 로드된 후에는 새 데이터가 아님을 표시
    }

    @PrePersist
    protected void onPrePersist() {

        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
    // 여기 까지 테스트 임시 
    // @PrePersist
    // protected void onCreate() {
    //     OffsetDateTime now = OffsetDateTime.now();
    //     this.createdAt = now;
    //     this.updatedAt = now;
    // }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // Getters & Setters
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public User getUser() { return user; }
    public void setUser(User user) { 
        this.user = user; 
        if (user != null) this.userId = user.getId();
    }

    public MentorStatus getStatus() { return status; }
    public void setStatus(MentorStatus status) { this.status = status; }

    public String getAbout() { return about; }
    public void setAbout(String about) { this.about = about; }

    public BigDecimal getRatingAvg() { return ratingAvg; }
    public void setRatingAvg(BigDecimal ratingAvg) { this.ratingAvg = ratingAvg; }

    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }

    public int getMenteeCount() { return menteeCount; }
    public void setMenteeCount(int menteeCount) { this.menteeCount = menteeCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
