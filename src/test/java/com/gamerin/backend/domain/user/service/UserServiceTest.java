package com.gamerin.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;
import com.gamerin.backend.domain.user.dto.request.UpdateProfileRequest;
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

    @Mock
    private MediaStorageService mediaStorageService;

    @Mock
    private MediaUploadSecurityService mediaUploadSecurityService;

    @Mock
    private LightweightSecurityScanService lightweightSecurityScanService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                followRepository,
                postRepository,
                postMediaRepository,
                mediaStorageService,
                mediaUploadSecurityService,
                lightweightSecurityScanService
        );
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

    @Test
    void uploadProfileImageStoresCompressedAvatarImage() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", "image".getBytes());
        MediaStorageService.PreparedMediaFile preparedFile =
                new MediaStorageService.PreparedMediaFile("compressed".getBytes(), ".jpg");
        MediaStorageService.StoredFile storedFile = new MediaStorageService.StoredFile(
                Path.of("uploads/profile-images/" + userId + "/profile/avatar.jpg"),
                "/uploads/profile-images/" + userId + "/profile/avatar.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(mediaUploadSecurityService.prepareProfileAvatarImage(file)).thenReturn(preparedFile);
        when(mediaStorageService.storeProfileImage(eq(userId), eq("profile"), eq(preparedFile))).thenReturn(storedFile);

        var response = userService.uploadProfileImage(userId, ProfileImageTarget.PROFILE, file);

        assertThat(response.target()).isEqualTo(ProfileImageTarget.PROFILE);
        assertThat(response.imageUrl()).isEqualTo("/uploads/profile-images/" + userId + "/profile/avatar.jpg");
        assertThat(response.sizeBytes()).isEqualTo(preparedFile.bytes().length);
        assertThat(user.getProfile().getProfileImageUrl()).isEqualTo("/uploads/profile-images/" + userId + "/profile/avatar.jpg");
        verify(lightweightSecurityScanService).assertFileClean(file);
    }

    @Test
    void uploadProfileImageStoresCompressedCoverImageAndDeletesPreviousOwnedCover() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");
        String previousCoverUrl = "/uploads/profile-images/" + userId + "/cover/11111111-1111-1111-1111-111111111111.jpg";
        user.getProfile().updateCoverImageUrl(previousCoverUrl);
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", "image".getBytes());
        MediaStorageService.PreparedMediaFile preparedFile =
                new MediaStorageService.PreparedMediaFile("compressed-cover".getBytes(), ".jpg");
        MediaStorageService.StoredFile storedFile = new MediaStorageService.StoredFile(
                Path.of("uploads/profile-images/" + userId + "/cover/cover.jpg"),
                "/uploads/profile-images/" + userId + "/cover/cover.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(mediaUploadSecurityService.prepareProfileCoverImage(file)).thenReturn(preparedFile);
        when(mediaStorageService.storeProfileImage(eq(userId), eq("cover"), eq(preparedFile))).thenReturn(storedFile);

        var response = userService.uploadProfileImage(userId, ProfileImageTarget.COVER, file);

        assertThat(response.target()).isEqualTo(ProfileImageTarget.COVER);
        assertThat(response.imageUrl()).isEqualTo("/uploads/profile-images/" + userId + "/cover/cover.jpg");
        assertThat(user.getProfile().getCoverImageUrl()).isEqualTo("/uploads/profile-images/" + userId + "/cover/cover.jpg");
        verify(mediaStorageService).deleteOwnedProfileImageUrlQuietly(userId, "cover", previousCoverUrl);
    }

    @Test
    void uploadProfileImageDeletesPreviousImageAfterCommitWhenTransactionIsActive() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");
        String previousCoverUrl = "/uploads/profile-images/" + userId + "/cover/11111111-1111-1111-1111-111111111111.jpg";
        user.getProfile().updateCoverImageUrl(previousCoverUrl);
        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", "image".getBytes());
        MediaStorageService.PreparedMediaFile preparedFile =
                new MediaStorageService.PreparedMediaFile("compressed-cover".getBytes(), ".jpg");
        MediaStorageService.StoredFile storedFile = new MediaStorageService.StoredFile(
                Path.of("uploads/profile-images/" + userId + "/cover/cover.jpg"),
                "/uploads/profile-images/" + userId + "/cover/cover.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(mediaUploadSecurityService.prepareProfileCoverImage(file)).thenReturn(preparedFile);
        when(mediaStorageService.storeProfileImage(eq(userId), eq("cover"), eq(preparedFile))).thenReturn(storedFile);

        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.uploadProfileImage(userId, ProfileImageTarget.COVER, file);

            verify(mediaStorageService, never()).deleteOwnedProfileImageUrlQuietly(any(), any(), any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(mediaStorageService).deleteOwnedProfileImageUrlQuietly(userId, "cover", previousCoverUrl);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadProfileImageDeletesStoredFileAfterRollbackWhenTransactionIsActive() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", "image".getBytes());
        MediaStorageService.PreparedMediaFile preparedFile =
                new MediaStorageService.PreparedMediaFile("compressed".getBytes(), ".jpg");
        MediaStorageService.StoredFile storedFile = new MediaStorageService.StoredFile(
                Path.of("uploads/profile-images/" + userId + "/profile/avatar.jpg"),
                "/uploads/profile-images/" + userId + "/profile/avatar.jpg"
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(mediaUploadSecurityService.prepareProfileAvatarImage(file)).thenReturn(preparedFile);
        when(mediaStorageService.storeProfileImage(eq(userId), eq("profile"), eq(preparedFile))).thenReturn(storedFile);

        TransactionSynchronizationManager.initSynchronization();
        try {
            userService.uploadProfileImage(userId, ProfileImageTarget.PROFILE, file);

            verify(mediaStorageService, never()).deleteQuietly(storedFile);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(mediaStorageService).deleteQuietly(storedFile);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadProfileImageRejectsAvatarLargerThanTwoMegabytes() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                "image/jpeg",
                new byte[(2 * 1024 * 1024) + 1]
        );

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> userService.uploadProfileImage(userId, ProfileImageTarget.PROFILE, file)
                )
                .hasMessageContaining("2MB 이하");
        verify(lightweightSecurityScanService, never()).assertFileClean(any());
        verify(mediaStorageService, never()).storeProfileImage(any(), any(), any());
    }

    @Test
    void updateProfileRejectsProfileImageUrlNotIssuedByUploadApi() {
        UUID userId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> userService.updateProfile(
                                userId,
                                new UpdateProfileRequest(
                                        null,
                                        null,
                                        "data:image/jpeg;base64,abc",
                                        null,
                                        null,
                                        null
                                )
                        )
                )
                .hasMessageContaining("업로드 API");
        verify(mediaStorageService, never()).deleteOwnedProfileImageUrlQuietly(any(), any(), any());
    }

    @Test
    void updateProfileRejectsOtherUsersProfileImageUrl() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        User user = savedUser(userId, "me", "Me");

        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> userService.updateProfile(
                                userId,
                                new UpdateProfileRequest(
                                        null,
                                        null,
                                        "/uploads/profile-images/" + otherUserId + "/profile/11111111-1111-1111-1111-111111111111.jpg",
                                        null,
                                        null,
                                        null
                                )
                        )
                )
                .hasMessageContaining("업로드 API");
        verify(mediaStorageService, never()).deleteOwnedProfileImageUrlQuietly(any(), any(), any());
    }

    private User savedUser(UUID id, String handle, String nickname) {
        User user = User.createLocal(handle + "@example.com", handle, nickname, "encoded-password");
        ReflectionTestUtils.setField(user, "id", id);
        UserProfile profile = UserProfile.createDefault(user);
        user.setProfile(profile);
        return user;
    }
}
