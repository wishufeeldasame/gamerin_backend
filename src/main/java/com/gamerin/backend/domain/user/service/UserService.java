package com.gamerin.backend.domain.user.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.dto.request.UpdateProfileRequest;
import com.gamerin.backend.domain.user.dto.response.DetailedUserProfileResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;

    public UserService(
            UserRepository userRepository,
            FollowRepository followRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository
    ) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
    }

    @Transactional(readOnly = true)
    public DetailedUserProfileResponse getMyProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return toProfileResponse(user);
    }

    @Transactional(readOnly = true)
    public DetailedUserProfileResponse getProfile(String handle) {
        User user = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return toProfileResponse(user);
    }

    private DetailedUserProfileResponse toProfileResponse(User user) {
        UserProfile profile = user.getProfile();

        long followersCount = followRepository.countByFolloweeId(user.getId());
        long followingCount = followRepository.countByFollowerId(user.getId());
        long postCount = postRepository.countByAuthorIdAndDeletedAtIsNull(user.getId());
        long mediaPostCount = postRepository.countMediaPostsByAuthorId(user.getId());
        long mediaItemCount = postMediaRepository.countActiveMediaByAuthorId(user.getId());

        return new DetailedUserProfileResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                profile != null ? profile.getBio() : null,
                profile != null ? profile.getLocation() : null,
                profile != null ? profile.getWebsite() : null,
                profile != null ? profile.getCoverImageUrl() : null,
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null ? profile.getGameStats() : null,
                profile != null && profile.isVerifiedBadge(),
                followersCount,
                followingCount,
                postCount,
                mediaPostCount,
                mediaItemCount
        );
    }

    @Transactional
    public void updateProfile(UUID userId, UpdateProfileRequest request) {
    // 1. 사용자 조회
    User user = userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

    // 2. 닉네임 수정 (제공된 경우만)
    if (request.getNickname() != null) {
        user.updateNickname(request.getNickname());
    }

    // 3. 프로필 정보 수정
    UserProfile profile = user.getProfile();
    // 기존에 프로필이 없는 경우를 대비해 null 체크 후 생성 로직이 필요할 수 있으나, 
    // 현재 구조상 1:1 필수 관계라면 바로 수정합니다.
    
    if (request.getBio() != null) profile.updateBio(request.getBio());
    if (request.getProfileImageUrl() != null) profile.updateProfileImageUrl(request.getProfileImageUrl());
    if (request.getCoverImageUrl() != null) profile.updateCoverImageUrl(request.getCoverImageUrl());
    if (request.getLocation() != null) profile.updateLocation(request.getLocation());
    if (request.getWebsite() != null) profile.updateWebsite(request.getWebsite());
    
    }
}
