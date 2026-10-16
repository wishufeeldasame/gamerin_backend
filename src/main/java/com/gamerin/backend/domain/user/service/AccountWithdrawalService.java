package com.gamerin.backend.domain.user.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.message.service.MessageRealtimeService;
import com.gamerin.backend.domain.post.service.MediaStorageService;
import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.repository.UserWithdrawalRepository;

/**
 * 회원 탈퇴: users 행은 남기고(status=DELETED, deletedAt) 개인정보를 익명화한다.
 * 게시글·댓글·팔로우·북마크·알림·소셜 연동은 정리하고, 좋아요·리포스트·보낸 DM·신고/제재·마일리지 원장은 보존한다.
 */
@Service
public class AccountWithdrawalService {

    private final UserRepository userRepository;
    private final UserWithdrawalRepository withdrawalRepository;
    private final MediaStorageService mediaStorageService;
    private final MessageRealtimeService messageRealtimeService;

    public AccountWithdrawalService(
            UserRepository userRepository,
            UserWithdrawalRepository withdrawalRepository,
            MediaStorageService mediaStorageService,
            MessageRealtimeService messageRealtimeService
    ) {
        this.userRepository = userRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.mediaStorageService = mediaStorageService;
        this.messageRealtimeService = messageRealtimeService;
    }

    @Transactional
    public void withdraw(UUID userId) {
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 프로그램을 먼저 잠가 검사 직후 새 신청이 끼어들지 못하게 한다(실패 시 함께 롤백)
        withdrawalRepository.softDeleteMentoringPrograms(userId);
        if (withdrawalRepository.existsUnsettledMentoring(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "진행 중이거나 정산되지 않은 멘토링이 있어 탈퇴할 수 없습니다. 멘토링을 완료하거나 취소한 뒤 다시 시도해 주세요.");
        }
        withdrawalRepository.deactivateMentorProfile(userId);

        withdrawalRepository.softDeletePosts(userId);
        withdrawalRepository.lockPostsCommentedBy(userId);
        withdrawalRepository.decreaseCommentCountsForAuthor(userId);
        withdrawalRepository.deleteActiveComments(userId);
        withdrawalRepository.deleteFollows(userId);
        withdrawalRepository.deleteBookmarkCollections(userId);
        withdrawalRepository.deletePostBookmarks(userId);
        withdrawalRepository.deleteNotifications(userId);
        withdrawalRepository.deleteMentionsOfUser(userId);
        withdrawalRepository.deleteSocialAccounts(userId);
        withdrawalRepository.revokeRefreshTokens(userId);
        withdrawalRepository.deletePasswordResetTokens(userId);

        UserProfile profile = user.getProfile();
        String profileImageUrl = profile != null ? profile.getProfileImageUrl() : null;
        String coverImageUrl = profile != null ? profile.getCoverImageUrl() : null;
        if (profile != null) {
            profile.clearForWithdrawal();
        }
        user.withdraw();

        runAfterCommit(() -> {
            deleteProfileImage(userId, ProfileImageTarget.PROFILE, profileImageUrl);
            deleteProfileImage(userId, ProfileImageTarget.COVER, coverImageUrl);
            messageRealtimeService.disconnect(userId);
        });
    }

    private void deleteProfileImage(UUID userId, ProfileImageTarget target, String url) {
        if (url != null && !url.isBlank()) {
            mediaStorageService.deleteOwnedProfileImageUrlQuietly(userId, target.getDirectorySegment(), url);
        }
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
