package com.gamerin.backend.domain.user.service;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String handle) throws UsernameNotFoundException {
        // 탈퇴(deletedAt != null) 및 삭제(DELETED) 계정은 로드 차단
        User user = userRepository.findByHandle(handle)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없거나 탈퇴한 계정입니다."));

        return CustomUserPrincipal.from(user);
    }

    public CustomUserPrincipal loadById(UUID userId) {
        // 탈퇴(deletedAt != null) 및 삭제(DELETED) 계정은 로드 차단
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없거나 탈퇴한 계정입니다."));

        return CustomUserPrincipal.from(user);
    }
}