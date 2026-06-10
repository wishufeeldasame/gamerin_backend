package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowRepository followRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, followRepository, postRepository, postMediaRepository);
    }

    @Test
    void getProfileReturnsFollowingStateForViewer() {
        UUID viewerId = UUID.randomUUID();
        UUID profileUserId = UUID.randomUUID();
        User profileUser = savedUser(profileUserId, "target", "Target");

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(profileUser));
        when(followRepository.existsByFollowerIdAndFolloweeId(viewerId, profileUserId)).thenReturn(true);

        var response = userService.getProfile(viewerId, "target");

        assertThat(response.id()).isEqualTo(profileUserId);
        assertThat(response.handle()).isEqualTo("target");
        assertThat(response.isFollowing()).isTrue();
    }

    @Test
    void getMyProfileDoesNotCalculateSelfFollowingState() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        var response = userService.getMyProfile(userId);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.isFollowing()).isFalse();
        verify(followRepository, never()).existsByFollowerIdAndFolloweeId(userId, userId);
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }
}
