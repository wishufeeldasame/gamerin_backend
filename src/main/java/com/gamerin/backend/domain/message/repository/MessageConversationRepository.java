package com.gamerin.backend.domain.message.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.message.entity.MessageConversation;

import jakarta.persistence.LockModeType;

public interface MessageConversationRepository extends JpaRepository<MessageConversation, UUID> {

    Optional<MessageConversation> findByIdAndDeletedAtIsNull(UUID id);

    Optional<MessageConversation> findByDirectKeyAndDeletedAtIsNull(String directKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select conversation
        from MessageConversation conversation
        where conversation.id = :id
          and conversation.deletedAt is null
        """)
    Optional<MessageConversation> findActiveByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select conversation
        from MessageConversation conversation
        where conversation.directKey = :directKey
          and conversation.deletedAt is null
        """)
    Optional<MessageConversation> findActiveByDirectKeyForUpdate(@Param("directKey") String directKey);

    @Modifying
    @Query(
            value = """
                insert into message_conversations (direct_key, type)
                values (:directKey, 'DIRECT')
                on conflict (direct_key) do nothing
                """,
            nativeQuery = true
    )
    int insertDirectConversationIfAbsent(@Param("directKey") String directKey);
}
