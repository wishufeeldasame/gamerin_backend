package com.gamerin.backend.domain.notification.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query(value = """
            SELECT CAST(n.id AS VARCHAR)
            FROM notifications n
            JOIN users actor ON actor.id = n.actor_id
            LEFT JOIN posts p ON p.id = n.post_id
            LEFT JOIN post_comments c ON c.id = n.comment_id
            WHERE n.recipient_id = :recipientId
              AND actor.deleted_at IS NULL
              AND (n.post_id IS NULL OR p.deleted_at IS NULL)
              AND (n.comment_id IS NULL OR c.deleted_at IS NULL)
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findValidPageIds(
            @Param("recipientId") UUID recipientId,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT CAST(n.id AS VARCHAR)
            FROM notifications n
            JOIN users actor ON actor.id = n.actor_id
            LEFT JOIN posts p ON p.id = n.post_id
            LEFT JOIN post_comments c ON c.id = n.comment_id
            WHERE n.recipient_id = :recipientId
              AND actor.deleted_at IS NULL
              AND (n.post_id IS NULL OR p.deleted_at IS NULL)
              AND (n.comment_id IS NULL OR c.deleted_at IS NULL)
              AND (
                    n.created_at < :cursorCreatedAt
                    OR (n.created_at = :cursorCreatedAt AND n.id < :cursorId)
              )
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findValidPageIdsBefore(
            @Param("recipientId") UUID recipientId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit
    );

    @Query("""
            SELECT n
            FROM Notification n
            JOIN FETCH n.actor actor
            LEFT JOIN FETCH actor.profile
            LEFT JOIN FETCH n.post post
            LEFT JOIN FETCH n.comment comment
            WHERE n.id IN :ids
              AND actor.deletedAt IS NULL
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
            """)
    List<Notification> findAllWithDetailsByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("""
            SELECT n
            FROM Notification n
            JOIN FETCH n.actor actor
            LEFT JOIN FETCH actor.profile
            LEFT JOIN FETCH n.post post
            LEFT JOIN FETCH n.comment comment
            WHERE n.id = :notificationId
              AND n.recipient.id = :recipientId
              AND actor.deletedAt IS NULL
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
            """)
    Optional<Notification> findValidByIdAndRecipientId(
            @Param("notificationId") UUID notificationId,
            @Param("recipientId") UUID recipientId
    );

    @Query("""
            SELECT COUNT(n)
            FROM Notification n
            JOIN n.actor actor
            LEFT JOIN n.post post
            LEFT JOIN n.comment comment
            WHERE n.recipient.id = :recipientId
              AND n.readAt IS NULL
              AND actor.deletedAt IS NULL
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
            """)
    long countValidUnreadByRecipientId(@Param("recipientId") UUID recipientId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Notification n
            SET n.readAt = :readAt
            WHERE n.recipient.id = :recipientId
              AND n.readAt IS NULL
              AND n.createdAt <= :cutoff
            """)
    int markAllReadBefore(
            @Param("recipientId") UUID recipientId,
            @Param("readAt") OffsetDateTime readAt,
            @Param("cutoff") OffsetDateTime cutoff
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.LIKE
              AND n.post.id = :postId
              AND n.actor.id = :actorId
            """)
    int deleteLikeNotification(
            @Param("postId") UUID postId,
            @Param("actorId") UUID actorId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.COMMENT
              AND n.comment.id = :commentId
            """)
    int deleteCommentNotification(@Param("commentId") UUID commentId);

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.FOLLOW
              AND n.actor.id = :followerId
              AND n.recipient.id = :followeeId
            """)
    int deleteFollowNotification(
            @Param("followerId") UUID followerId,
            @Param("followeeId") UUID followeeId
    );
}
