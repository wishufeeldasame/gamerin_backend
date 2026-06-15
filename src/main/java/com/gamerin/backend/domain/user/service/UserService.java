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

        return toProfileResponse(user, false);
    }

    @Transactional(readOnly = true)
    public DetailedUserProfileResponse getProfile(UUID viewerId, String handle) {
        User user = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        boolean isFollowing = viewerId != null
                && !viewerId.equals(user.getId())
                && followRepository.existsByFollowerIdAndFolloweeId(viewerId, user.getId());

        return toProfileResponse(user, isFollowing);
    }

    private DetailedUserProfileResponse toProfileResponse(User user, boolean isFollowing) {
        UserProfile profile = user.getProfile();

        long followersCount = followRepository.countActiveFollowersByFolloweeId(user.getId());
        long followingCount = followRepository.countActiveFollowingByFollowerId(user.getId());
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
                isFollowing,
                followersCount,
                followingCount,
                postCount,
                mediaPostCount,
                mediaItemCount
        );
    }

    @Transactional
    public void updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (request.getNickname() != null) {
            user.updateNickname(request.getNickname());
        }

        UserProfile profile = user.getProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 프로필을 찾을 수 없습니다.");
        }

        if (request.getBio() != null) {
            profile.updateBio(request.getBio());
        }
        if (request.getProfileImageUrl() != null) {
            profile.updateProfileImageUrl(request.getProfileImageUrl());
        }
        if (request.getCoverImageUrl() != null) {
            profile.updateCoverImageUrl(request.getCoverImageUrl());
        }
        if (request.getLocation() != null) {
            profile.updateLocation(request.getLocation());
        }
        if (request.getWebsite() != null) {
            profile.updateWebsite(request.getWebsite());
        }
    }
}
