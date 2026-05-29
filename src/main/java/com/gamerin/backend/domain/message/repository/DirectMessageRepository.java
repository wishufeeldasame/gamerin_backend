package com.gamerin.backend.domain.message.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.message.entity.DirectMessage;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, UUID> {

    Optional<DirectMessage> findByIdAndConversationIdAndDeletedAtIsNull(UUID id, UUID conversationId);

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
        order by dm.createdAt asc, dm.id asc
        """)
    List<DirectMessage> findActiveByConversationId(@Param("conversationId") UUID conversationId);

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findRecentActiveByConversationId(
            @Param("conversationId") UUID conversationId,
            Pageable pageable
    );

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findActivePageByConversationId(
            @Param("conversationId") UUID conversationId,
            Pageable pageable
    );

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
          and dm.createdAt < :cursorCreatedAt
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findActivePageByConversationIdBefore(
            @Param("conversationId") UUID conversationId,
            @Param("cursorCreatedAt") java.time.OffsetDateTime cursorCreatedAt,
            Pageable pageable
    );

    @Query("""
        select count(dm.id)
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.sender.id <> :viewerId
          and dm.deletedAt is null
          and dm.createdAt > :lastReadAt
        """)
    long countUnreadMessages(
            @Param("conversationId") UUID conversationId,
            @Param("viewerId") UUID viewerId,
            @Param("lastReadAt") java.time.OffsetDateTime lastReadAt
    );

    @Query("""
        select count(dm.id)
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.sender.id <> :viewerId
          and dm.deletedAt is null
        """)
    long countUnreadMessagesWithoutReadAt(
            @Param("conversationId") UUID conversationId,
            @Param("viewerId") UUID viewerId
    );
}
