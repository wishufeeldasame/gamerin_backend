package com.gamerin.backend.domain.user.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.user.entity.User;

/**
 * 회원 탈퇴 시 여러 도메인에 걸친 연관 데이터를 일괄 정리한다.
 * users 행은 소프트 삭제하므로 FK CASCADE에 기대지 않고 필요한 행만 직접 지운다.
 */
public interface UserWithdrawalRepository extends Repository<User, UUID> {

    // 멘토(내 프로그램) 또는 멘티로서 아직 끝나지 않았거나 에스크로가 잡힌 신청
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM mentoring_applications a
                JOIN mentoring_programs p ON p.id = a.program_id
                WHERE (a.mentee_id = :userId OR p.mentor_id = :userId)
                  AND (a.status IN ('APPLIED', 'ACCEPTED', 'ONGOING', 'FINISHED')
                       OR a.payment_status = 'ESCROW_HELD')
            )
            """, nativeQuery = true)
    boolean existsUnsettledMentoring(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE mentoring_programs SET deleted_at = now() WHERE mentor_id = :userId AND deleted_at IS NULL",
            nativeQuery = true)
    int softDeleteMentoringPrograms(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE mentor_profiles SET status = 'INACTIVE', about = NULL WHERE user_id = :userId",
            nativeQuery = true)
    int deactivateMentorProfile(@Param("userId") UUID userId);

    // 기존 게시글 삭제와 같은 소프트 삭제 — PostCleanupService가 보존 기간 뒤 파일까지 영구 삭제한다
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE posts SET deleted_at = now() WHERE author_id = :userId AND deleted_at IS NULL",
            nativeQuery = true)
    int softDeletePosts(@Param("userId") UUID userId);

    // 댓글 수 집계 전에 대상 게시글을 잠가 동시 댓글 삭제·신고 숨김과의 이중 차감을 막는다(기존 흐름과 같이 posts → post_comments 순)
    @Query(value = """
            SELECT p.id
            FROM posts p
            WHERE p.id IN (SELECT c.post_id FROM post_comments c WHERE c.author_id = :userId AND c.deleted_at IS NULL)
            ORDER BY p.id
            FOR UPDATE
            """, nativeQuery = true)
    List<UUID> lockPostsCommentedBy(@Param("userId") UUID userId);

    // 기존 댓글 삭제와 같이 노출 중인 댓글은 행을 지우고 댓글 수를 맞춘다(신고로 숨긴 댓글은 정리 스케줄러에 맡김)
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE posts p
            SET comment_count = GREATEST(p.comment_count - c.cnt, 0)
            FROM (
                SELECT post_id, COUNT(*) AS cnt
                FROM post_comments
                WHERE author_id = :userId AND deleted_at IS NULL
                GROUP BY post_id
            ) c
            WHERE p.id = c.post_id
            """, nativeQuery = true)
    int decreaseCommentCountsForAuthor(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM post_comments WHERE author_id = :userId AND deleted_at IS NULL", nativeQuery = true)
    int deleteActiveComments(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM follows WHERE follower_id = :userId OR followee_id = :userId", nativeQuery = true)
    int deleteFollows(@Param("userId") UUID userId);

    // 컬렉션 항목은 FK CASCADE로 함께 삭제된다
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM bookmark_collections WHERE user_id = :userId", nativeQuery = true)
    int deleteBookmarkCollections(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM post_bookmarks WHERE user_id = :userId", nativeQuery = true)
    int deletePostBookmarks(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM notifications WHERE recipient_id = :userId OR actor_id = :userId", nativeQuery = true)
    int deleteNotifications(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_mentions WHERE mentioned_user_id = :userId", nativeQuery = true)
    int deleteMentionsOfUser(@Param("userId") UUID userId);

    // 소셜 연동을 끊어야 같은 구글 계정으로 새로 가입할 수 있고, 탈퇴 계정으로 다시 로그인되지 않는다
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM social_accounts WHERE user_id = :userId", nativeQuery = true)
    int deleteSocialAccounts(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL",
            nativeQuery = true)
    int revokeRefreshTokens(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM password_reset_tokens WHERE user_id = :userId", nativeQuery = true)
    int deletePasswordResetTokens(@Param("userId") UUID userId);
}
