package com.gamerin.backend.global.security.principal;

import com.gamerin.backend.domain.user.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class CustomUserPrincipal implements UserDetails {

    private final UUID userId;
    private final String handle;
    private final String passwordHash;
    private final String nickname;
    private final String role;
    private final boolean active;

    private CustomUserPrincipal(UUID userId, String handle, String passwordHash, String nickname, String role, boolean active) {
        this.userId = userId;
        this.handle = handle;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
        this.active = active;
    }

    public static CustomUserPrincipal from(User user) {
        return new CustomUserPrincipal(
                user.getId(),
                user.getHandle(),
                user.getPasswordHash(),
                user.getNickname(),
                user.getRole().name(),
                user.isActive()
        );
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
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
