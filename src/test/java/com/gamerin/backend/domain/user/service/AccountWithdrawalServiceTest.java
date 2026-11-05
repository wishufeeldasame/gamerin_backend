package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.service.MessageRealtimeService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.entity.UserStatus;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.repository.UserWithdrawalRepository;

class AccountWithdrawalServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserWithdrawalRepository withdrawalRepository = mock(UserWithdrawalRepository.class);
    private final MediaStorageService mediaStorageService = mock(MediaStorageService.class);
    private final MessageRealtimeService messageRealtimeService = mock(MessageRealtimeService.class);
    private final AccountWithdrawalService service = new AccountWithdrawalService(
            userRepository, withdrawalRepository, mediaStorageService, messageRealtimeService);

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.createLocal("leaver@example.com", "leaver", "Leaver", "hash");
        ReflectionTestUtils.setField(user, "id", userId);
        UserProfile profile = UserProfile.createDefault(user);
        profile.updateBio("bio");
        profile.updateProfileImageUrl("/uploads/profile-images/" + userId + "/profile/a.jpg");
        user.setProfile(profile);
        when(userRepository.findActiveByIdForUpdate(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void withdrawAnonymizesAccountAndRunsCleanup() {
        service.withdraw(userId);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getHandle()).isEqualTo("deleted_" + userId.toString().replace("-", ""));
        assertThat(user.getNickname()).isEqualTo(User.WITHDRAWN_NICKNAME);
        assertThat(user.getProfile().getBio()).isNull();
        assertThat(user.getProfile().getProfileImageUrl()).isNull();

        verify(withdrawalRepository).softDeletePosts(userId);
        verify(withdrawalRepository).deleteActiveComments(userId);
        verify(withdrawalRepository).deleteSocialAccounts(userId);
        verify(withdrawalRepository).revokeRefreshTokens(userId);
        // 트랜잭션 밖(단위 테스트)에서는 커밋 후 작업이 즉시 실행된다
        verify(mediaStorageService).deleteOwnedProfileImageUrlQuietly(
                userId, "profile", "/uploads/profile-images/" + userId + "/profile/a.jpg");
        verify(messageRealtimeService).disconnect(userId);
    }

    @Test
    void withdrawIsRejectedWhileMentoringIsUnsettled() {
        when(withdrawalRepository.existsUnsettledMentoring(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.withdraw(userId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(withdrawalRepository, never()).softDeletePosts(userId);
        verify(messageRealtimeService, never()).disconnect(userId);
    }

    @Test
    void withdrawOfMissingOrAlreadyDeletedUserIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findActiveByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(unknownId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
