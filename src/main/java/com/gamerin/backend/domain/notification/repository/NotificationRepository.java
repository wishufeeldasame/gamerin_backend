package com.gamerin.backend.domain.notification.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.notification.entity.Notification;

import jakarta.persistence.LockModeType;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query(value = """
            SELECT CAST(n.id AS VARCHAR)
            FROM notifications n
            LEFT JOIN users actor ON actor.id = n.actor_id
            LEFT JOIN posts p ON p.id = n.post_id
            LEFT JOIN post_comments c ON c.id = n.comment_id
            LEFT JOIN message_conversations mc ON mc.id = n.conversation_id
            LEFT JOIN direct_messages dm ON dm.id = n.message_id
            WHERE n.recipient_id = :recipientId
              AND (n.actor_id IS NULL OR actor.deleted_at IS NULL)
              AND (n.post_id IS NULL OR p.deleted_at IS NULL)
              AND (n.comment_id IS NULL OR c.deleted_at IS NULL)
              AND (n.conversation_id IS NULL OR mc.deleted_at IS NULL)
              AND (n.message_id IS NULL OR dm.deleted_at IS NULL)
            ORDER BY n.event_at DESC, n.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findValidPageIds(
            @Param("recipientId") UUID recipientId,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT CAST(n.id AS VARCHAR)
            FROM notifications n
            LEFT JOIN users actor ON actor.id = n.actor_id
            LEFT JOIN posts p ON p.id = n.post_id
            LEFT JOIN post_comments c ON c.id = n.comment_id
            LEFT JOIN message_conversations mc ON mc.id = n.conversation_id
            LEFT JOIN direct_messages dm ON dm.id = n.message_id
            WHERE n.recipient_id = :recipientId
              AND (n.actor_id IS NULL OR actor.deleted_at IS NULL)
              AND (n.post_id IS NULL OR p.deleted_at IS NULL)
              AND (n.comment_id IS NULL OR c.deleted_at IS NULL)
              AND (n.conversation_id IS NULL OR mc.deleted_at IS NULL)
              AND (n.message_id IS NULL OR dm.deleted_at IS NULL)
              AND (
                    n.event_at < :cursorCreatedAt
                    OR (n.event_at = :cursorCreatedAt AND n.id < :cursorId)
              )
            ORDER BY n.event_at DESC, n.id DESC
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
            LEFT JOIN FETCH n.actor actor
            LEFT JOIN FETCH actor.profile
            LEFT JOIN FETCH n.post post
            LEFT JOIN FETCH n.comment comment
            LEFT JOIN FETCH n.conversation conversation
            LEFT JOIN FETCH n.message message
            WHERE n.id IN :ids
              AND (n.actor IS NULL OR actor.deletedAt IS NULL)
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
              AND (n.conversation IS NULL OR conversation.deletedAt IS NULL)
              AND (n.message IS NULL OR message.deletedAt IS NULL)
            """)
    List<Notification> findAllWithDetailsByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("""
            SELECT n
            FROM Notification n
            LEFT JOIN FETCH n.actor actor
            LEFT JOIN FETCH actor.profile
            LEFT JOIN FETCH n.post post
            LEFT JOIN FETCH n.comment comment
            LEFT JOIN FETCH n.conversation conversation
            LEFT JOIN FETCH n.message message
            WHERE n.id = :notificationId
              AND n.recipient.id = :recipientId
              AND (n.actor IS NULL OR actor.deletedAt IS NULL)
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
              AND (n.conversation IS NULL OR conversation.deletedAt IS NULL)
              AND (n.message IS NULL OR message.deletedAt IS NULL)
            """)
    Optional<Notification> findValidByIdAndRecipientId(
            @Param("notificationId") UUID notificationId,
            @Param("recipientId") UUID recipientId
    );

    @Query("""
            SELECT COUNT(n)
            FROM Notification n
            LEFT JOIN n.actor actor
            LEFT JOIN n.post post
            LEFT JOIN n.comment comment
            LEFT JOIN n.conversation conversation
            LEFT JOIN n.message message
            WHERE n.recipient.id = :recipientId
              AND n.readAt IS NULL
              AND (n.actor IS NULL OR actor.deletedAt IS NULL)
              AND (n.post IS NULL OR post.deletedAt IS NULL)
              AND (n.comment IS NULL OR comment.deletedAt IS NULL)
              AND (n.conversation IS NULL OR conversation.deletedAt IS NULL)
              AND (n.message IS NULL OR message.deletedAt IS NULL)
            """)
    long countValidUnreadByRecipientId(@Param("recipientId") UUID recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT n
            FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.DIRECT_MESSAGE
              AND n.recipient.id = :recipientId
              AND n.conversation.id = :conversationId
            """)
    Optional<Notification> findDirectMessageForUpdate(
            @Param("recipientId") UUID recipientId,
            @Param("conversationId") UUID conversationId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Notification n
            SET n.readAt = :readAt
            WHERE n.recipient.id = :recipientId
              AND n.readAt IS NULL
              AND n.eventAt <= :cutoff
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

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.REPOST
              AND n.post.id = :postId
              AND n.actor.id = :actorId
            """)
    int deleteRepostNotification(
            @Param("postId") UUID postId,
            @Param("actorId") UUID actorId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.type = com.gamerin.backend.domain.notification.entity.NotificationType.DIRECT_MESSAGE
              AND n.recipient.id = :recipientId
              AND n.conversation.id = :conversationId
            """)
    int deleteDirectMessageNotification(
            @Param("recipientId") UUID recipientId,
            @Param("conversationId") UUID conversationId
    );
}
