package com.gamerin.backend.domain.follow.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.entity.Follow;
import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
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

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
