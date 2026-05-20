package com.gamerin.backend.domain.message.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.message.entity.MessageParticipant;

public interface MessageParticipantRepository extends JpaRepository<MessageParticipant, UUID> {

    boolean existsByConversationIdAndUserIdAndDeletedAtIsNull(UUID conversationId, UUID userId);

    Optional<MessageParticipant> findByConversationIdAndUserIdAndDeletedAtIsNull(UUID conversationId, UUID userId);

    List<MessageParticipant> findByConversationIdAndDeletedAtIsNull(UUID conversationId);

    List<MessageParticipant> findByUserIdAndDeletedAtIsNull(UUID userId);
}
