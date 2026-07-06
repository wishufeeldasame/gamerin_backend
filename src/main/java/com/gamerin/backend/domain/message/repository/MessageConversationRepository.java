package com.gamerin.backend.domain.message.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.message.entity.MessageConversation;

public interface MessageConversationRepository extends JpaRepository<MessageConversation, UUID> {

    Optional<MessageConversation> findByIdAndDeletedAtIsNull(UUID id);

    Optional<MessageConversation> findByDirectKeyAndDeletedAtIsNull(String directKey);

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
