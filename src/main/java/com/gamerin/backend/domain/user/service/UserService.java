package com.gamerin.backend.domain.user.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.dto.response.UserProfileResponse;
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
    public UserProfileResponse getMyProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return toProfileResponse(user);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String handle) {
        User user = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return toProfileResponse(user);
    }

    private UserProfileResponse toProfileResponse(User user) {
        UserProfile profile = user.getProfile();

        long followersCount = followRepository.countByFolloweeId(user.getId());
        long followingCount = followRepository.countByFollowerId(user.getId());
        long postCount = postRepository.countByAuthorIdAndDeletedAtIsNull(user.getId());
        long mediaPostCount = postRepository.countMediaPostsByAuthorId(user.getId());
        long mediaItemCount = postMediaRepository.countActiveMediaByAuthorId(user.getId());

        return new UserProfileResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                profile != null ? profile.getBio() : null,
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
}
