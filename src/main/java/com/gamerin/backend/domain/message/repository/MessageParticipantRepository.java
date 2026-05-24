package com.gamerin.backend.domain.message.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.message.entity.MessageParticipant;

public interface MessageParticipantRepository extends JpaRepository<MessageParticipant, UUID> {

    boolean existsByConversationIdAndUserIdAndDeletedAtIsNull(UUID conversationId, UUID userId);

    Optional<MessageParticipant> findByConversationIdAndUserIdAndDeletedAtIsNull(UUID conversationId, UUID userId);

    List<MessageParticipant> findByConversationIdAndDeletedAtIsNull(UUID conversationId);

    @Query("""
        select mp
        from MessageParticipant mp
        join fetch mp.conversation
        where mp.user.id = :userId
          and mp.deletedAt is null
          and mp.conversation.deletedAt is null
        order by mp.conversation.updatedAt desc, mp.conversation.id desc
        """)
    List<MessageParticipant> findActiveByUserIdWithConversation(@Param("userId") UUID userId);
}
