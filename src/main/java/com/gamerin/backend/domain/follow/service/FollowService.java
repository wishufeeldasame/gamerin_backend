package com.gamerin.backend.domain.follow.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.dto.response.FollowUserResponse;
import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
@Transactional
public class FollowService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final FollowRepository followRepository;

    public FollowService(UserRepository userRepository, FollowRepository followRepository) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
    }

    public void follow(CustomUserPrincipal principal, String handle) {
        User follower = getCurrentUser(principal);
        User followee = getTargetUser(handle);

        if (follower.getId().equals(followee.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot follow yourself.");
        }

        if (followRepository.existsByFollowerIdAndFolloweeId(follower.getId(), followee.getId())) {
            return;
        }

        try {
            followRepository.saveAndFlush(Follow.create(follower, followee));
        } catch (DataIntegrityViolationException e) {
            if (followRepository.existsByFollowerIdAndFolloweeId(follower.getId(), followee.getId())) {
                return;
            }
            throw e;
        }
    }

    public void unfollow(CustomUserPrincipal principal, String handle) {
        User follower = getCurrentUser(principal);
        User followee = getTargetUser(handle);

        followRepository.findByFollowerIdAndFolloweeId(follower.getId(), followee.getId())
                .ifPresent(followRepository::delete);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<FollowUserResponse> getFollowers(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        User viewer = getCurrentUser(principal);
        User target = getTargetUser(handle);
        int pageSize = clampSize(size);
        FollowCursor cursorValue = parseCursor(cursor);

        List<UUID> followIds = cursorValue == null
                ? followRepository.findFollowerPageIds(target.getId(), pageSize + 1)
                : followRepository.findFollowerPageIdsBefore(
                        target.getId(),
                        cursorValue.createdAt(),
                        cursorValue.followId(),
                        pageSize + 1
                );

        return toPageResponse(followIds, pageSize, viewer.getId(), Follow::getFollower);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<FollowUserResponse> getFollowing(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        User viewer = getCurrentUser(principal);
        User target = getTargetUser(handle);
        int pageSize = clampSize(size);
        FollowCursor cursorValue = parseCursor(cursor);

        List<UUID> followIds = cursorValue == null
                ? followRepository.findFollowingPageIds(target.getId(), pageSize + 1)
                : followRepository.findFollowingPageIdsBefore(
                        target.getId(),
                        cursorValue.createdAt(),
                        cursorValue.followId(),
                        pageSize + 1
                );

        return toPageResponse(followIds, pageSize, viewer.getId(), Follow::getFollowee);
    }

    private CursorPageResponse<FollowUserResponse> toPageResponse(
            List<UUID> loadedFollowIds,
            int pageSize,
            UUID viewerId,
            Function<Follow, User> listedUserExtractor
    ) {
        boolean hasNext = loadedFollowIds.size() > pageSize;
        List<UUID> pageFollowIds = hasNext
                ? new ArrayList<>(loadedFollowIds.subList(0, pageSize))
                : new ArrayList<>(loadedFollowIds);

        if (pageFollowIds.isEmpty()) {
            return new CursorPageResponse<>(List.of(), null, false);
        }

        List<Follow> follows = findFollowsInOrder(pageFollowIds);
        List<User> listedUsers = follows.stream()
                .map(listedUserExtractor)
                .toList();
        Set<UUID> followingIds = findFollowingIds(viewerId, listedUsers);

        List<FollowUserResponse> items = follows.stream()
                .map(follow -> {
                    User listedUser = listedUserExtractor.apply(follow);
                    return toFollowUserResponse(
                            listedUser,
                            follow.getCreatedAt(),
                            followingIds.contains(listedUser.getId())
                    );
                })
                .toList();

        String nextCursor = hasNext && !follows.isEmpty()
                ? buildCursor(follows.get(follows.size() - 1))
                : null;

        return new CursorPageResponse<>(items, nextCursor, hasNext);
    }

    private List<Follow> findFollowsInOrder(List<UUID> ids) {
        Map<UUID, Follow> followsById = followRepository.findAllWithUsersByIdIn(ids).stream()
                .collect(Collectors.toMap(Follow::getId, Function.identity()));

        return ids.stream()
                .map(followsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private Set<UUID> findFollowingIds(UUID viewerId, Collection<User> users) {
        Set<UUID> userIds = users.stream()
                .map(User::getId)
                .filter(userId -> !viewerId.equals(userId))
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return Set.of();
        }

        return new HashSet<>(followRepository.findFolloweeIdsByFollowerIdAndFolloweeIdIn(viewerId, userIds));
    }

    private FollowUserResponse toFollowUserResponse(User user, OffsetDateTime followedAt, boolean isFollowing) {
        UserProfile profile = user.getProfile();

        return new FollowUserResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                profile != null ? profile.getBio() : null,
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null && profile.isVerifiedBadge(),
                isFollowing,
                followedAt
        );
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private User getTargetUser(String handle) {
        return userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private FollowCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String decodedCursor = decodeCursor(cursor);
            String[] parts = decodedCursor.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Cursor must contain createdAt and followId.");
            }
            return new FollowCursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
        }
    }

    private String buildCursor(Follow follow) {
        String payload = follow.getCreatedAt() + "|" + follow.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return cursor;
        }
    }

    private record FollowCursor(OffsetDateTime createdAt, UUID followId) {
    }
}
