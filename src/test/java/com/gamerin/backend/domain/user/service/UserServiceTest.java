package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.gamerin.backend.domain.user.dto.response.UserProfileResponse;
import com.gamerin.backend.domain.user.entity.User;
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
        UUID targetId = UUID.randomUUID();
        User target = savedUser(targetId, "target", "Target");

        when(userRepository.findByHandleAndDeletedAtIsNull("target")).thenReturn(Optional.of(target));
        when(followRepository.existsByFollowerIdAndFolloweeId(viewerId, targetId)).thenReturn(true);

        UserProfileResponse response = userService.getProfile(viewerId, "target");

        assertThat(response.isFollowing()).isTrue();
    }

    @Test
    void getMyProfileIsNotFollowing() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.getMyProfile(userId);

        assertThat(response.isFollowing()).isFalse();
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
