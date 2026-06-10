package com.gamerin.backend.domain.follow.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
        User followee = findUserByHandle(handle);

        if (follower.getId().equals(followee.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot follow yourself.");
        }

        if (followRepository.existsByFollowerIdAndFolloweeId(follower.getId(), followee.getId())) {
            return;
        }

        followRepository.save(Follow.create(follower, followee));
    }

    public void unfollow(CustomUserPrincipal principal, String handle) {
        User follower = getCurrentUser(principal);
        User followee = findUserByHandle(handle);

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
        UUID viewerId = getCurrentUser(principal).getId();
        User targetUser = findUserByHandle(handle);
        int pageSize = clampSize(size);
        FollowCursor followCursor = parseCursor(cursor);

        List<Follow> loadedFollows = loadFollowers(targetUser.getId(), followCursor, pageSize + 1);
        boolean hasNext = loadedFollows.size() > pageSize;
        List<Follow> pageFollows = hasNext ? loadedFollows.subList(0, pageSize) : loadedFollows;

        return new CursorPageResponse<>(
                toFollowUserResponses(pageFollows, viewerId, true),
                buildCursor(pageFollows, hasNext),
                hasNext
        );
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<FollowUserResponse> getFollowing(
            CustomUserPrincipal principal,
            String handle,
            String cursor,
            int size
    ) {
        UUID viewerId = getCurrentUser(principal).getId();
        User targetUser = findUserByHandle(handle);
        int pageSize = clampSize(size);
        FollowCursor followCursor = parseCursor(cursor);

        List<Follow> loadedFollows = loadFollowing(targetUser.getId(), followCursor, pageSize + 1);
        boolean hasNext = loadedFollows.size() > pageSize;
        List<Follow> pageFollows = hasNext ? loadedFollows.subList(0, pageSize) : loadedFollows;

        return new CursorPageResponse<>(
                toFollowUserResponses(pageFollows, viewerId, false),
                buildCursor(pageFollows, hasNext),
                hasNext
        );
    }

    private User getCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findByIdAndDeletedAtIsNull(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found."));
    }

    private User findUserByHandle(String handle) {
        return userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));
    }

    private List<Follow> loadFollowers(UUID followeeId, FollowCursor cursor, int limit) {
        List<UUID> followIds = cursor == null
                ? followRepository.findFollowerPageIds(followeeId, limit)
                : followRepository.findFollowerPageIdsBefore(followeeId, cursor.createdAt(), cursor.followId(), limit);
        return findFollowsInOrder(followIds);
    }

    private List<Follow> loadFollowing(UUID followerId, FollowCursor cursor, int limit) {
        List<UUID> followIds = cursor == null
                ? followRepository.findFollowingPageIds(followerId, limit)
                : followRepository.findFollowingPageIdsBefore(followerId, cursor.createdAt(), cursor.followId(), limit);
        return findFollowsInOrder(followIds);
    }

    private List<Follow> findFollowsInOrder(List<UUID> followIds) {
        if (followIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < followIds.size(); index++) {
            order.put(followIds.get(index), index);
        }

        List<Follow> follows = new ArrayList<>(followRepository.findAllWithUsersByIdIn(followIds));
        follows.sort((left, right) -> Integer.compare(order.get(left.getId()), order.get(right.getId())));
        return follows;
    }

    private List<FollowUserResponse> toFollowUserResponses(List<Follow> follows, UUID viewerId, boolean followerList) {
        List<User> users = follows.stream()
                .map(follow -> followerList ? follow.getFollower() : follow.getFollowee())
                .toList();
        Set<UUID> followingIds = findFollowingIds(viewerId, users);

        return follows.stream()
                .map(follow -> {
                    User user = followerList ? follow.getFollower() : follow.getFollowee();
                    return toFollowUserResponse(user, follow.getCreatedAt(), followingIds.contains(user.getId()));
                })
                .toList();
    }

    private Set<UUID> findFollowingIds(UUID viewerId, List<User> users) {
        List<UUID> userIds = users.stream()
                .map(User::getId)
                .filter(userId -> !userId.equals(viewerId))
                .toList();

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

    private int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private FollowCursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String decodedCursor = decodeCursor(raw.trim());
        String[] values = decodedCursor.split("\\|");
        if (values.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor.");
        }

        try {
            return new FollowCursor(OffsetDateTime.parse(values[0]), UUID.fromString(values[1]));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor.", e);
        }
    }

    private String buildCursor(List<Follow> follows, boolean hasNext) {
        if (!hasNext || follows.isEmpty()) {
            return null;
        }

        Follow last = follows.get(follows.size() - 1);
        return encodeCursor(last.getCreatedAt() + "|" + last.getId());
    }

    private String encodeCursor(String raw) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String raw) {
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(raw);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Keep accepting the original raw cursor shape for backward compatibility.
            return raw;
        }
    }

    private record FollowCursor(OffsetDateTime createdAt, UUID followId) {
    }
}
