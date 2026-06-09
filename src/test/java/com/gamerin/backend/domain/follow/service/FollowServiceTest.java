package com.gamerin.backend.domain.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.dto.response.FollowUserResponse;
import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    private FollowService followService;

    @BeforeEach
    void setUp() {
        followService = new FollowService(userRepository, followRepository);
    }

    @Test
    void followRejectsSelfFollow() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "tester", "Tester");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(userRepository.findByHandleAndDeletedAtIsNull("tester")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> followService.follow(CustomUserPrincipal.from(user), "tester"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.BAD_REQUEST.value());

        verify(followRepository, never()).save(any(Follow.class));
    }

    @Test
    void followCreatesRelationshipWhenMissing() {
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        User follower = savedUser(followerId, "me", "Me");
        User followee = savedUser(followeeId, "other", "Other");

        when(userRepository.findByIdAndDeletedAtIsNull(followerId)).thenReturn(Optional.of(follower));
        when(userRepository.findByHandleAndDeletedAtIsNull("other")).thenReturn(Optional.of(followee));
        when(followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)).thenReturn(false);

        followService.follow(CustomUserPrincipal.from(follower), "other");

        verify(followRepository).save(any(Follow.class));
    }

    @Test
    void getFollowersReturnsUsersWithFollowingState() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        User viewer = savedUser(viewerId, "viewer", "Viewer");
        User target = savedUser(targetId, "target", "Target");
        User follower = savedUser(followerId, "follower", "Follower");
        Follow follow = savedFollow(UUID.randomUUID(), follower, target, OffsetDateTime.now());

        when(userRepository.findByIdAndDeletedAtIsNull(viewerId)).thenReturn(Optional.of(viewer));
        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(target));
        when(followRepository.findFollowerPageIds(targetId, 2)).thenReturn(List.of(follow.getId()));
        when(followRepository.findAllWithUsersByIdIn(List.of(follow.getId()))).thenReturn(List.of(follow));
        when(followRepository.findFolloweeIdsByFollowerIdAndFolloweeIdIn(viewerId, List.of(followerId)))
                .thenReturn(List.of(followerId));

        CursorPageResponse<FollowUserResponse> response =
                followService.getFollowers(CustomUserPrincipal.from(viewer), "target", null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();

        FollowUserResponse item = response.items().get(0);
        assertThat(item.userId()).isEqualTo(followerId);
        assertThat(item.handle()).isEqualTo("follower");
        assertThat(item.nickname()).isEqualTo("Follower");
        assertThat(item.isFollowing()).isTrue();
    }

    @Test
    void getFollowingReturnsUsersWithCursorWhenMoreItemsExist() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID firstFolloweeId = UUID.randomUUID();
        UUID secondFolloweeId = UUID.randomUUID();
        User viewer = savedUser(viewerId, "viewer", "Viewer");
        User target = savedUser(targetId, "target", "Target");
        User firstFollowee = savedUser(firstFolloweeId, "first", "First");
        User secondFollowee = savedUser(secondFolloweeId, "second", "Second");
        OffsetDateTime now = OffsetDateTime.now();
        Follow firstFollow = savedFollow(UUID.randomUUID(), target, firstFollowee, now);
        Follow secondFollow = savedFollow(UUID.randomUUID(), target, secondFollowee, now.minusSeconds(1));

        when(userRepository.findByIdAndDeletedAtIsNull(viewerId)).thenReturn(Optional.of(viewer));
        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(target));
        when(followRepository.findFollowingPageIds(targetId, 2))
                .thenReturn(List.of(firstFollow.getId(), secondFollow.getId()));
        when(followRepository.findAllWithUsersByIdIn(List.of(firstFollow.getId(), secondFollow.getId())))
                .thenReturn(List.of(secondFollow, firstFollow));
        when(followRepository.findFolloweeIdsByFollowerIdAndFolloweeIdIn(viewerId, List.of(firstFolloweeId)))
                .thenReturn(List.of());

        CursorPageResponse<FollowUserResponse> response =
                followService.getFollowing(CustomUserPrincipal.from(viewer), "target", null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(firstFollow.getCreatedAt() + "|" + firstFollow.getId());

        FollowUserResponse item = response.items().get(0);
        assertThat(item.userId()).isEqualTo(firstFolloweeId);
        assertThat(item.handle()).isEqualTo("first");
        assertThat(item.nickname()).isEqualTo("First");
        assertThat(item.isFollowing()).isFalse();
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Follow savedFollow(UUID id, User follower, User followee, OffsetDateTime createdAt) {
        Follow follow = Follow.create(follower, followee);
        ReflectionTestUtils.setField(follow, "id", id);
        ReflectionTestUtils.setField(follow, "createdAt", createdAt);
        return follow;
    }
}
