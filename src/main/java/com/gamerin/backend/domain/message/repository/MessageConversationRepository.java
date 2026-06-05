package com.gamerin.backend.domain.message.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.message.entity.MessageConversation;

public interface MessageConversationRepository extends JpaRepository<MessageConversation, UUID> {

    Optional<MessageConversation> findByIdAndDeletedAtIsNull(UUID id);

    Optional<MessageConversation> findByDirectKeyAndDeletedAtIsNull(String directKey);
}
