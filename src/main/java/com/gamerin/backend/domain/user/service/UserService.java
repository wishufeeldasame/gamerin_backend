package com.gamerin.backend.domain.user.service;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.follow.repository.FollowRepository;
import com.gamerin.backend.domain.post.service.LightweightSecurityScanService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.post.service.MediaUploadSecurityService;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;
import com.gamerin.backend.domain.user.dto.request.UpdateProfileRequest;
import com.gamerin.backend.domain.user.dto.response.DetailedUserProfileResponse;
import com.gamerin.backend.domain.user.dto.response.ProfileImageUploadResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Service
@Transactional
public class UserService {

    private static final long MAX_PROFILE_AVATAR_UPLOAD_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_PROFILE_COVER_UPLOAD_BYTES = 5L * 1024L * 1024L;
    private static final String UUID_JPEG_FILE_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg";

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PostRepository postRepository;
    private final PostMediaRepository postMediaRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaUploadSecurityService mediaUploadSecurityService;
    private final LightweightSecurityScanService lightweightSecurityScanService;

    public UserService(
            UserRepository userRepository,
            FollowRepository followRepository,
            PostRepository postRepository,
            PostMediaRepository postMediaRepository,
            MediaStorageService mediaStorageService,
            MediaUploadSecurityService mediaUploadSecurityService,
            LightweightSecurityScanService lightweightSecurityScanService
    ) {
        this.userRepository = userRepository;
        this.followRepository = followRepository;
        this.postRepository = postRepository;
        this.postMediaRepository = postMediaRepository;
        this.mediaStorageService = mediaStorageService;
        this.mediaUploadSecurityService = mediaUploadSecurityService;
        this.lightweightSecurityScanService = lightweightSecurityScanService;
    }

    @Transactional(readOnly = true)
    public DetailedUserProfileResponse getMyProfile(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return toProfileResponse(user, false);
    }

    @Transactional(readOnly = true)
    public DetailedUserProfileResponse getProfile(UUID viewerId, String handle) {
        User user = userRepository.findByHandleAndDeletedAtIsNull(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        boolean isFollowing = viewerId != null
                && !viewerId.equals(user.getId())
                && followRepository.existsByFollowerIdAndFolloweeId(viewerId, user.getId());

        return toProfileResponse(user, isFollowing);
    }

    private DetailedUserProfileResponse toProfileResponse(User user, boolean isFollowing) {
        UserProfile profile = user.getProfile();

        long followersCount = followRepository.countActiveFollowersByFolloweeId(user.getId());
        long followingCount = followRepository.countActiveFollowingByFollowerId(user.getId());
        long postCount = postRepository.countByAuthorIdAndDeletedAtIsNull(user.getId());
        long mediaPostCount = postRepository.countMediaPostsByAuthorId(user.getId());
        long mediaItemCount = postMediaRepository.countActiveMediaByAuthorId(user.getId());

        return new DetailedUserProfileResponse(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                profile != null ? profile.getBio() : null,
                profile != null ? profile.getLocation() : null,
                profile != null ? profile.getWebsite() : null,
                profile != null ? profile.getCoverImageUrl() : null,
                profile != null ? profile.getProfileImageUrl() : null,
                profile != null ? profile.getGameStats() : null,
                profile != null && profile.isVerifiedBadge(),
                isFollowing,
                followersCount,
                followingCount,
                postCount,
                mediaPostCount,
                mediaItemCount
        );
    }

    @Transactional
    public void updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (request.getNickname() != null) {
            user.updateNickname(request.getNickname());
        }

        UserProfile profile = user.getProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 프로필을 찾을 수 없습니다.");
        }

        if (request.getBio() != null) {
            profile.updateBio(request.getBio());
        }
        if (request.getProfileImageUrl() != null) {
            validateProfileImageUrl(userId, request.getProfileImageUrl(), ProfileImageTarget.PROFILE);
            String previousProfileImageUrl = profile.getProfileImageUrl();
            profile.updateProfileImageUrl(request.getProfileImageUrl());
            deleteReplacedProfileImageAfterCommit(
                    userId,
                    ProfileImageTarget.PROFILE,
                    previousProfileImageUrl,
                    request.getProfileImageUrl()
            );
        }
        if (request.getCoverImageUrl() != null) {
            validateProfileImageUrl(userId, request.getCoverImageUrl(), ProfileImageTarget.COVER);
            String previousCoverImageUrl = profile.getCoverImageUrl();
            profile.updateCoverImageUrl(request.getCoverImageUrl());
            deleteReplacedProfileImageAfterCommit(
                    userId,
                    ProfileImageTarget.COVER,
                    previousCoverImageUrl,
                    request.getCoverImageUrl()
            );
        }
        if (request.getLocation() != null) {
            profile.updateLocation(request.getLocation());
        }
        if (request.getWebsite() != null) {
            profile.updateWebsite(request.getWebsite());
        }
    }

    private void validateProfileImageUrl(UUID userId, String imageUrl, ProfileImageTarget target) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        String requiredPrefix = "/uploads/profile-images/" + userId + "/" + target.getDirectorySegment() + "/";
        String allowedPattern = Pattern.quote(requiredPrefix) + UUID_JPEG_FILE_PATTERN;
        if (!Pattern.matches(allowedPattern, imageUrl)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로필 이미지 URL은 업로드 API가 발급한 경로만 사용할 수 있습니다."
            );
        }
    }

    @Transactional
    public ProfileImageUploadResponse uploadProfileImage(UUID userId, ProfileImageTarget target, MultipartFile file) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        UserProfile profile = user.getProfile();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자 프로필을 찾을 수 없습니다.");
        }

        validateProfileImageUpload(target, file);
        lightweightSecurityScanService.assertFileClean(file);

        MediaStorageService.PreparedMediaFile preparedFile = target == ProfileImageTarget.PROFILE
                ? mediaUploadSecurityService.prepareProfileAvatarImage(file)
                : mediaUploadSecurityService.prepareProfileCoverImage(file);

        MediaStorageService.StoredFile storedFile = null;
        try {
            storedFile = mediaStorageService.storeProfileImage(userId, target.getDirectorySegment(), preparedFile);
            deleteStoredFileAfterRollback(storedFile);
            String previousUrl;
            if (target == ProfileImageTarget.PROFILE) {
                previousUrl = profile.getProfileImageUrl();
                profile.updateProfileImageUrl(storedFile.publicUrl());
            } else {
                previousUrl = profile.getCoverImageUrl();
                profile.updateCoverImageUrl(storedFile.publicUrl());
            }

            deleteReplacedProfileImageAfterCommit(userId, target, previousUrl, storedFile.publicUrl());
            return new ProfileImageUploadResponse(target, storedFile.publicUrl(), preparedFile.bytes().length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "프로필 이미지를 저장하지 못했습니다.", ex);
        } catch (RuntimeException ex) {
            mediaStorageService.deleteQuietly(storedFile);
            throw ex;
        }
    }

    private void validateProfileImageUpload(ProfileImageTarget target, MultipartFile file) {
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "프로필 이미지 대상을 선택해주세요.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "업로드할 이미지 파일을 선택해주세요.");
        }

        long maxBytes = target == ProfileImageTarget.PROFILE
                ? MAX_PROFILE_AVATAR_UPLOAD_BYTES
                : MAX_PROFILE_COVER_UPLOAD_BYTES;
        if (file.getSize() > maxBytes) {
            long maxMegabytes = maxBytes / 1024L / 1024L;
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "프로필 " + (target == ProfileImageTarget.PROFILE ? "이미지" : "커버") + "는 "
                            + maxMegabytes + "MB 이하로 업로드해주세요."
            );
        }
    }

    private void deleteReplacedProfileImageAfterCommit(
            UUID userId,
            ProfileImageTarget target,
            String previousUrl,
            String nextUrl
    ) {
        if (previousUrl == null || previousUrl.isBlank() || previousUrl.equals(nextUrl)) {
            return;
        }

        Runnable cleanup = () -> mediaStorageService.deleteOwnedProfileImageUrlQuietly(
                userId,
                target.getDirectorySegment(),
                previousUrl
        );
        runAfterCommit(cleanup);
    }

    private void deleteStoredFileAfterRollback(MediaStorageService.StoredFile storedFile) {
        if (storedFile == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    mediaStorageService.deleteQuietly(storedFile);
                }
            }
        });
    }

    private void runAfterCommit(Runnable cleanup) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }
}
