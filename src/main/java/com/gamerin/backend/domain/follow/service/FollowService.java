package com.gamerin.backend.domain.follow.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional
public class FollowService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    public FollowService(UserRepository userRepository, FollowRepository followRepository) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
    }

    public void follow(CustomUserPrincipal principal, String handle) {
        User follower = getCurrentUser(principal);
        User followee = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (follower.getId().equals(followee.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신은 팔로우할 수 없습니다.");
        }

        if (followRepository.existsByFollowerIdAndFolloweeId(follower.getId(), followee.getId())) {
            return;
        }

        followRepository.save(Follow.create(follower, followee));
    }

    public void unfollow(CustomUserPrincipal principal, String handle) {
        User follower = getCurrentUser(principal);
        User followee = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        followRepository.findByFollowerIdAndFolloweeId(follower.getId(), followee.getId())
                .ifPresent(followRepository::delete);
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }
}
