package com.gamerin.backend.global.security.principal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class CustomUserPrincipal implements UserDetails {

    private final UUID userId;
    private final String handle;
    private final String passwordHash;
    private final String nickname;
    private final String role;
    private final boolean active;
    private final UserStatus status; // 실시간 계정 상태 보관

    private CustomUserPrincipal(UUID userId, String handle, String passwordHash, String nickname, String role, boolean active, UserStatus status) {
        this.userId = userId;
        this.handle = handle;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
        this.active = active;
        this.status = status;
    }

    public static CustomUserPrincipal from(User user) {
        return new CustomUserPrincipal(
                user.getId(),
                user.getHandle(),
                user.getPasswordHash(),
                user.getNickname(),
                user.getRole().name(),
                user.isActive(),
                user.getStatus() // User의 현재 상태 포함
        );
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public UserStatus getStatus() {
        return status;
    }

    // 정지 계정 여부 확인 (UserSuspensionFilter에서 중복 DB 조회 없이 사용)
    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return handle;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return active;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}