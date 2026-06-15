package com.gamerin.backend.domain.follow.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
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

        verify(followRepository).saveAndFlush(any(Follow.class));
    }

    @Test
    void followIgnoresDuplicateCreatedByConcurrentRequest() {
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        User follower = savedUser(followerId, "me", "Me");
        User followee = savedUser(followeeId, "other", "Other");

        when(userRepository.findByIdAndDeletedAtIsNull(followerId)).thenReturn(Optional.of(follower));
        when(userRepository.findByHandleAndDeletedAtIsNull("other")).thenReturn(Optional.of(followee));
        when(followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId))
                .thenReturn(false, true);
        when(followRepository.saveAndFlush(any(Follow.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate follow"));

        followService.follow(CustomUserPrincipal.from(follower), "other");

        verify(followRepository).saveAndFlush(any(Follow.class));
    }

    @Test
    void getFollowersReturnsUsersWithFollowingState() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        UUID followId = UUID.randomUUID();
        OffsetDateTime followedAt = OffsetDateTime.parse("2026-06-11T10:15:30+09:00");

        User viewer = savedUser(viewerId, "viewer", "Viewer");
        User target = savedUser(targetId, "target", "Target");
        User follower = savedUser(followerId, "fan", "Fan");
        Follow follow = savedFollow(followId, follower, target, followedAt);

        when(userRepository.findByIdAndDeletedAtIsNull(viewerId)).thenReturn(Optional.of(viewer));
        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(target));
        when(followRepository.findFollowerPageIds(targetId, 21)).thenReturn(List.of(followId.toString()));
        when(followRepository.findAllWithUsersByIdIn(List.of(followId))).thenReturn(List.of(follow));
        when(followRepository.findFolloweeIdsByFollowerIdAndFolloweeIdIn(eq(viewerId), any()))
                .thenReturn(List.of(followerId));

        CursorPageResponse<?> response = followService.getFollowers(
                CustomUserPrincipal.from(viewer),
                "target",
                null,
                20
        );

        assertThat(response.items()).hasSize(1);
        Object item = response.items().get(0);
        assertThat(item)
                .hasFieldOrPropertyWithValue("userId", followerId)
                .hasFieldOrPropertyWithValue("handle", "fan")
                .hasFieldOrPropertyWithValue("nickname", "Fan")
                .hasFieldOrPropertyWithValue("isFollowing", true)
                .hasFieldOrPropertyWithValue("followedAt", followedAt);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void getFollowingUsesStableCursorForNextPage() {
        UUID viewerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UUID firstFollowId = UUID.randomUUID();
        UUID secondFollowId = UUID.randomUUID();
        OffsetDateTime firstFollowedAt = OffsetDateTime.parse("2026-06-11T11:00:00+09:00");
        OffsetDateTime secondFollowedAt = OffsetDateTime.parse("2026-06-11T10:00:00+09:00");

        User viewer = savedUser(viewerId, "viewer", "Viewer");
        User target = savedUser(targetId, "target", "Target");
        User firstUser = savedUser(firstUserId, "first", "First");
        User secondUser = savedUser(secondUserId, "second", "Second");
        Follow firstFollow = savedFollow(firstFollowId, target, firstUser, firstFollowedAt);
        Follow secondFollow = savedFollow(secondFollowId, target, secondUser, secondFollowedAt);

        when(userRepository.findByIdAndDeletedAtIsNull(viewerId)).thenReturn(Optional.of(viewer));
        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(target));
        when(followRepository.findFollowingPageIds(targetId, 2))
                .thenReturn(List.of(firstFollowId.toString(), secondFollowId.toString()));
        when(followRepository.findAllWithUsersByIdIn(List.of(firstFollowId))).thenReturn(List.of(firstFollow));
        when(followRepository.findFollowingPageIdsBefore(targetId, firstFollowedAt, firstFollowId, 2))
                .thenReturn(List.of(secondFollowId.toString()));
        when(followRepository.findAllWithUsersByIdIn(List.of(secondFollowId))).thenReturn(List.of(secondFollow));
        when(followRepository.findFolloweeIdsByFollowerIdAndFolloweeIdIn(eq(viewerId), any()))
                .thenReturn(List.of(), List.of());

        var firstPage = followService.getFollowing(CustomUserPrincipal.from(viewer), "target", null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).handle()).isEqualTo("first");
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(firstPage.nextCursor()).doesNotContain("|");

        var secondPage = followService.getFollowing(CustomUserPrincipal.from(viewer), "target", firstPage.nextCursor(), 1);

        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).handle()).isEqualTo("second");
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }

    private Follow savedFollow(UUID id, User follower, User followee, OffsetDateTime createdAt) {
        Follow follow = Follow.create(follower, followee);
        ReflectionTestUtils.setField(follow, "id", id);
        ReflectionTestUtils.setField(follow, "createdAt", createdAt);
        return follow;
    }
}
