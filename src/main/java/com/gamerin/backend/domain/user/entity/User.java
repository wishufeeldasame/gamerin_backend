package com.gamerin.backend.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false, unique = true, length = 50)
    private String handle;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "provider", length = 50)  // 소셜 제공자 : 어떤 소셜인지 ex "google"
    private String provider;

    @Column(name = "provider_id", length = 255) // 부여받은 고유 번호
    private String providerId;

    protected User() {
    }

    public static User createLocal(String email, String handle, String nickname, String passwordHash) {
        User user = new User();
        user.email = email;
        user.handle = handle;
        user.nickname = nickname;
        user.passwordHash = passwordHash;
        user.role = UserRole.USER;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public static User createSocial(String email, String handle, String nickname, String provider, String providerId) {
        User user = new User();
        user.email = email;
        user.handle = handle;
        user.nickname = nickname;
        user.provider = provider;
        user.providerId = providerId;
        user.passwordHash = null; // 소셜 가입자는 비밀번호를 설정하지 않음
        user.role = UserRole.USER;
        user.status = UserStatus.ACTIVE;
        return user;
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

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getHandle() {
        return handle;
    }

    public String getNickname() {
        return nickname;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void updateLastLoginAt() {
        this.lastLoginAt = OffsetDateTime.now();
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }
}
