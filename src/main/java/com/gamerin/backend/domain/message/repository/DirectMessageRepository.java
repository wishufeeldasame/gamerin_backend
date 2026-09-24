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
    // 삭제되지 않은 유효 메시지 존재 여부 확인 (신고 대상 검증용)
    boolean existsByIdAndDeletedAtIsNull(UUID id);

    // 대화 참여자 본인만 접근 가능한 유효 메시지 단건 조회 (신고 권한 검증 및 스냅샷 생성용)
    @Query("""
            select dm
            from DirectMessage dm
            join MessageParticipant mp on mp.conversation = dm.conversation
            where dm.id = :messageId
              and dm.deletedAt is null
              and mp.user.id = :userId
              and mp.deletedAt is null
            """)
    Optional<DirectMessage> findActiveByIdAndParticipantUserId(
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId
    );
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
          and dm.createdAt > :clearedAt
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findRecentActiveByConversationIdAfter(
            @Param("conversationId") UUID conversationId,
            @Param("clearedAt") java.time.OffsetDateTime clearedAt,
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
          and dm.createdAt > :clearedAt
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findActivePageByConversationIdAfter(
            @Param("conversationId") UUID conversationId,
            @Param("clearedAt") java.time.OffsetDateTime clearedAt,
            Pageable pageable
    );

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
          and (
              dm.createdAt < :cursorCreatedAt
              or (dm.createdAt = :cursorCreatedAt and dm.id < :cursorId)
          )
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findActivePageByConversationIdBefore(
            @Param("conversationId") UUID conversationId,
            @Param("cursorCreatedAt") java.time.OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    @Query("""
        select dm
        from DirectMessage dm
        where dm.conversation.id = :conversationId
          and dm.deletedAt is null
          and (
              dm.createdAt < :cursorCreatedAt
              or (dm.createdAt = :cursorCreatedAt and dm.id < :cursorId)
          )
          and dm.createdAt > :clearedAt
        order by dm.createdAt desc, dm.id desc
        """)
    List<DirectMessage> findActivePageByConversationIdBeforeAndAfter(
            @Param("conversationId") UUID conversationId,
            @Param("cursorCreatedAt") java.time.OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            @Param("clearedAt") java.time.OffsetDateTime clearedAt,
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
