package com.gamerin.backend.domain.user.service;

import com.gamerin.backend.domain.user.entity.User;
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
        // 활성 상태(status == ACTIVE && deletedAt == null)인 사용자만 인증 로드 허용
        User user = userRepository.findByHandle(handle)
                .filter(User::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없거나 비활성화된 계정입니다."));
        return CustomUserPrincipal.from(user);
    }

    public CustomUserPrincipal loadById(UUID userId) {
        // JWT/SSE 인증 시 활성 상태(status == ACTIVE && deletedAt == null)인 사용자만 로드
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없거나 비활성화된 계정입니다."));
        return CustomUserPrincipal.from(user);
    }
}